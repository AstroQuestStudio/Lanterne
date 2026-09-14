package fr.clubcitrouille.lanterne.content.shop;

import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * La caisse : l'endroit, et le seul, où l'argent et les objets changent de main.
 *
 * <h2>L'invariant que tout ce fichier existe pour tenir</h2>
 *
 * <p><b>Jamais d'argent sans objet, jamais d'objet sans argent.</b> Un duplicateur d'objets ou
 * d'argent ne fait pas « un bogue de plus » sur un serveur : il rend l'économie sans objet, et
 * l'économie ne se répare pas — il faut la refaire, et avec elle la confiance des joueurs.
 *
 * <p>Deux propriétés donnent cet invariant, et elles ne se ressemblent pas :
 *
 * <ol>
 *   <li><b>À la vente, l'argent versé est une fonction pure de ce qui a été réellement détruit.</b>
 *       {@link #drain} retire des objets et rend le nombre qu'il a retiré ; le crédit se calcule sur
 *       ce nombre-là, jamais sur celui qui a été demandé. Il est donc <em>impossible</em> d'être payé
 *       pour un objet qu'on a encore : il n'existe pas de chemin dans ce code qui crédite avant de
 *       retirer.</li>
 *   <li><b>À l'achat, le débit est plafonné par ce qui a été réellement remis.</b> La place est
 *       vérifiée avant le débit ; et si malgré cela l'insertion laissait un reste — ce qui ne devrait
 *       pas arriver —, la différence est <em>recréditée immédiatement</em>. La vérification préalable
 *       et le remboursement font double emploi, et c'est voulu : la première donne un message clair,
 *       le second garantit le résultat même si la première se trompe.</li>
 * </ol>
 *
 * <h2>Pourquoi aucun verrou n'est nécessaire</h2>
 *
 * <p>Une transaction entière — lecture du solde, calcul, débit, insertion — s'exécute d'un seul tenant
 * sur le <b>fil du serveur</b>, sans un seul point de suspension. Les paquets entrants y sont renvoyés
 * par {@code IPayloadContext.enqueueWork}, les commandes y sont déjà. Il n'existe donc aucun instant
 * où deux transactions du même joueur seraient à moitié faites — et c'est une garantie structurelle,
 * pas une synchronisation qu'on aurait pu oublier de poser quelque part.
 *
 * <h2>Ce que la caisse refuse de racheter, et pourquoi c'est un refus de sécurité</h2>
 *
 * <p>Tout objet <b>porteur de composants</b> ou <b>abîmé</b>. Un catalogue donne un prix à
 * {@code minecraft:diamond_pickaxe} ; il ne dit rien d'une pioche en diamant Fortune III, renommée,
 * à trois points de durabilité. Racheter les trois au même prix, c'est soit dépouiller un joueur, soit
 * — bien pire — lui offrir une machine à faire de l'argent : acheter l'objet neuf, l'user, le revendre
 * au prix du neuf. Le test tient en un appel, {@code isComponentsPatchEmpty}, et il couvre d'un coup
 * les enchantements, les noms personnalisés, la durabilité, les potions, les livres écrits, les
 * contenus de sacs, et tout ce qu'un autre mod ajoutera après nous.
 */
public final class Till {
    /** Ce qu'une transaction peut donner. Le client en compose la phrase — voir {@code Counter}. */
    public enum Outcome {
        /** L'échange a eu lieu. */
        DONE,
        /** Solde insuffisant. {@code detail} porte les centimes manquants. */
        NO_MONEY,
        /** Pas assez de place. {@code detail} porte les unités qui ne rentrent pas. */
        NO_ROOM,
        /** Pas assez d'objets propres. {@code detail} porte les unités manquantes. */
        NO_ITEMS,
        /** Cet objet n'est pas au catalogue. */
        UNKNOWN,
        /** La boutique ne reprend pas cet objet. */
        NOT_BOUGHT,
        /** Le compte est au plafond ; on ne peut plus être payé. */
        FULL_PURSE,
        /** Quantité hors des bornes du serveur. {@code detail} porte le lot maximal. */
        BAD_COUNT,
        /** La boutique est désactivée. */
        OFF
    }

    /**
     * Le ticket de caisse.
     *
     * @param outcome ce qui s'est passé
     * @param detail  un nombre dont le sens dépend de {@link Outcome}
     * @param count   les unités réellement échangées — zéro si la transaction a été refusée
     * @param total   les centimes réellement mouvementés
     */
    public record Receipt(Outcome outcome, long detail, int count, long total) {
        public boolean done() {
            return this.outcome == Outcome.DONE;
        }

        static Receipt no(Outcome outcome, long detail) {
            return new Receipt(outcome, detail, 0, 0L);
        }
    }

    private Till() {}

    // --- Achat -------------------------------------------------------------

    /**
     * Le joueur achète {@code wanted} unités à la boutique.
     *
     * <p>Le prix est arrêté <b>une fois</b>, avant la transaction, et vaut pour tout le lot. Un lot de
     * soixante-quatre coûte donc exactement soixante-quatre fois le prix affiché : c'est ce qui permet
     * à l'écran de montrer un total avant validation et de ne pas mentir. La contrepartie est qu'un
     * gros lot s'achète au prix d'avant sa propre dérive — d'où le plafond {@code lot_max}.
     */
    public static Receipt buy(ServerPlayer player, Identifier id, int wanted) {
        if (!Tariff.active()) {
            return Receipt.no(Outcome.OFF, 0L);
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return Receipt.no(Outcome.OFF, 0L);
        }
        if (wanted < 1 || wanted > Tariff.batch()) {
            return Receipt.no(Outcome.BAD_COUNT, Tariff.batch());
        }
        Offer offer = Stall.find(id);
        if (offer == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return Receipt.no(Outcome.UNKNOWN, 0L);
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.AIR) {
            return Receipt.no(Outcome.UNKNOWN, 0L);
        }

        Ledger ledger = Ledger.of(server);
        long unit = Drift.buy(offer, ledger.volume(id));
        long total;
        try {
            total = Math.multiplyExact(unit, (long) wanted);
        } catch (ArithmeticException tooBig) {
            return Receipt.no(Outcome.BAD_COUNT, Tariff.batch());
        }

        long purse = ledger.balance(player.getUUID());
        if (purse < total) {
            return Receipt.no(Outcome.NO_MONEY, total - purse);
        }
        ItemStack sample = new ItemStack(item);
        int room = room(player, sample);
        if (room < wanted) {
            return Receipt.no(Outcome.NO_ROOM, wanted - room);
        }

        String name = player.getGameProfile().name();
        if (!ledger.debit(player.getUUID(), name, total)) {
            // Inatteignable : le solde vient d'être lu sur le même fil, dans le même tick. Le test
            // reste, parce qu'un jour quelqu'un appellera cette méthode depuis ailleurs.
            return Receipt.no(Outcome.NO_MONEY, total - purse);
        }
        int given = pour(player, item, wanted);
        if (given < wanted) {
            // Le filet de sécurité annoncé dans l'en-tête. Il ne devrait jamais se déclencher ;
            // s'il se déclenche, le joueur est remboursé au centime et rien n'est perdu.
            ledger.credit(player.getUUID(), name, unit * (long) (wanted - given));
        }
        if (given > 0) {
            ledger.trade(id, given);
        }
        player.inventoryMenu.broadcastChanges();
        return new Receipt(Outcome.DONE, 0L, given, unit * (long) given);
    }

    // --- Vente -------------------------------------------------------------

    /**
     * Le joueur vend {@code wanted} unités à la boutique.
     *
     * <p>La place dans le compte est vérifiée <b>avant</b> de retirer quoi que ce soit : sans cela, un
     * joueur au plafond verrait ses objets disparaître contre un crédit écrêté. Le refus est explicite,
     * et il vaut mieux qu'un demi-paiement.
     *
     * <h2>Les deux prix, et lequel compte</h2>
     *
     * <p>Depuis les puits ({@link Toll}), il y a un <b>cours du marché</b>, le même pour tout le
     * monde, et un <b>prix personnel</b> — le cours moins ce que les franchises de ce joueur
     * retiennent. C'est le prix personnel qui est payé, et c'est le <b>cours</b> qui alimente les
     * compteurs. Ne pas confondre les deux est tout l'équilibrage : faire monter les compteurs avec
     * le net créerait une boucle de retour qui ramollit la retenue au moment précis où elle devrait
     * serrer.
     *
     * <p>La part est arrêtée <b>une fois</b>, avant la transaction, comme le prix. Un lot de mille
     * unités est donc payé au tarif du premier, et non à un tarif qui se dégraderait en cours de
     * route — ce qui rendrait le total affiché par l'écran impossible à tenir. La conséquence est
     * assumée : on peut franchir sa franchise d'un coup, au bon tarif. Le lot maximal du serveur
     * borne l'affaire, et le compteur, lui, enregistre bien la totalité.
     */
    public static Receipt sell(ServerPlayer player, Identifier id, int wanted) {
        if (!Tariff.active()) {
            return Receipt.no(Outcome.OFF, 0L);
        }
        if (!Tariff.buyback()) {
            return Receipt.no(Outcome.NOT_BOUGHT, 0L);
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return Receipt.no(Outcome.OFF, 0L);
        }
        if (wanted < 1 || wanted > Tariff.batch()) {
            return Receipt.no(Outcome.BAD_COUNT, Tariff.batch());
        }
        Offer offer = Stall.find(id);
        if (offer == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return Receipt.no(Outcome.UNKNOWN, 0L);
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.AIR) {
            return Receipt.no(Outcome.UNKNOWN, 0L);
        }

        Ledger ledger = Ledger.of(server);
        long market = Drift.sell(offer, ledger.volume(id));
        if (market <= 0L) {
            return Receipt.no(Outcome.NOT_BOUGHT, 0L);
        }
        int keep = Toll.keep(ledger, player.getUUID(), id);
        long unit = Toll.net(market, keep);
        if (unit <= 0L) {
            return Receipt.no(Outcome.NOT_BOUGHT, 0L);
        }
        int have = held(player, item);
        if (have < wanted) {
            return Receipt.no(Outcome.NO_ITEMS, wanted - have);
        }
        long headroom = Coin.CEILING - ledger.balance(player.getUUID());
        if (headroom < unit) {
            return Receipt.no(Outcome.FULL_PURSE, 0L);
        }
        if (headroom / unit < wanted) {
            return Receipt.no(Outcome.FULL_PURSE, headroom / unit);
        }

        int taken = drain(player, item, wanted);
        if (taken <= 0) {
            return Receipt.no(Outcome.NO_ITEMS, wanted);
        }
        // Le total suit CE QUI A ÉTÉ RETIRÉ, jamais ce qui a été demandé. C'est toute la garantie.
        long total = unit * (long) taken;
        ledger.credit(player.getUUID(), player.getGameProfile().name(), total);
        ledger.trade(id, -taken);
        // Les compteurs suivent le BRUT — voir l'en-tête de cette méthode. Et la différence est
        // comptée à part : elle n'est versée à personne, et /banque masse doit pouvoir le dire.
        ledger.earn(player.getUUID(), id, market * (long) taken);
        ledger.withhold((market - unit) * (long) taken);
        player.inventoryMenu.broadcastChanges();
        return new Receipt(Outcome.DONE, 0L, taken, total);
    }

    // --- L'inventaire, vu de la caisse -------------------------------------

    /**
     * Un objet tel que la boutique l'accepte : le bon objet, nu, intact.
     *
     * <p>{@code isDamaged()} fait double emploi avec {@code isComponentsPatchEmpty()} — la durabilité
     * <em>est</em> un composant, et un objet sans correctif de composants a la durabilité par défaut
     * de son type, c'est-à-dire aucune usure. Le test reste, parce qu'il dit à haute voix ce qu'on
     * refuse, et qu'il coûte une comparaison d'entiers.
     */
    public static boolean plain(ItemStack stack, Item item) {
        return !stack.isEmpty() && stack.getItem() == item
                && stack.isComponentsPatchEmpty() && !stack.isDamaged();
    }

    /** Combien d'unités propres de cet objet le joueur possède, hors équipement. */
    public static int held(Player player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (plain(stack, item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Combien d'unités de cet objet le joueur peut encore ranger.
     *
     * <p>Le compte est délibérément <b>prudent</b> : il ne regarde que les trente-six emplacements du
     * sac, alors que {@code Inventory.add} sait aussi remplir la main secondaire. La place réelle est
     * donc toujours supérieure ou égale à celle-ci. On peut donc refuser un achat qui serait passé
     * — jamais l'inverse, et c'est le seul sens qui compte.
     */
    public static int room(Player player, ItemStack sample) {
        int max = sample.getMaxStackSize();
        int room = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                room += max;
            } else if (ItemStack.isSameItemSameComponents(stack, sample) && stack.isStackable()) {
                room += Math.max(0, Math.min(max, stack.getMaxStackSize()) - stack.getCount());
            }
        }
        return room;
    }

    /**
     * Range des objets dans le sac, pile par pile.
     *
     * <p>{@code Inventory.add} <b>mute la pile qu'on lui passe</b> : ce qui reste dedans est ce qui n'a
     * pas pu être rangé. C'est cette valeur, et non le booléen rendu, qui dit la vérité — le booléen
     * est faux pour « rien n'est passé », pas pour « tout n'est pas passé ».
     *
     * @return le nombre d'unités réellement rangées
     */
    private static int pour(ServerPlayer player, Item item, int wanted) {
        int max = Math.max(1, new ItemStack(item).getMaxStackSize());
        int given = 0;
        while (given < wanted) {
            int slice = Math.min(max, wanted - given);
            ItemStack parcel = new ItemStack(item, slice);
            player.getInventory().add(parcel);
            int placed = slice - parcel.getCount();
            given += placed;
            if (placed <= 0) {
                break;
            }
        }
        return given;
    }

    /**
     * Retire des objets du sac.
     *
     * @return le nombre d'unités réellement retirées — la seule base de calcul du paiement
     */
    private static int drain(ServerPlayer player, Item item, int wanted) {
        NonNullList<ItemStack> bag = player.getInventory().getNonEquipmentItems();
        int left = wanted;
        for (int slot = 0; slot < bag.size() && left > 0; slot++) {
            ItemStack stack = bag.get(slot);
            if (!plain(stack, item)) {
                continue;
            }
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) {
                bag.set(slot, ItemStack.EMPTY);
            }
            left -= take;
        }
        return wanted - left;
    }
}
