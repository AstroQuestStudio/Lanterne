package fr.clubcitrouille.lanterne.core;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Le transfert par lots : seize objets d'un coup, et seize fois la recharge.
 *
 * <h2>Le coût d'un entonnoir n'est pas là où on le croit</h2>
 *
 * <p>Le tick d'un entonnoir se lit en quatre lignes, et la première décide de tout :
 *
 * <pre>
 * entity.cooldownTime--;
 * if (!entity.isOnCooldown()) {
 *     tryMoveItems(level, pos, state, entity, () -&gt; suckInItems(level, entity));
 * }
 * </pre>
 *
 * <p>Après chaque transfert réussi, {@code setCooldown(8)} impose huit ticks de recharge. Un
 * entonnoir <b>en marche</b> ne travaille donc qu'un tick sur huit ; les sept autres décrémentent un
 * compteur et repartent. C'est déjà peu, et c'est pourquoi la première tentative de ce projet — faire
 * dormir l'entonnoir <em>pendant</em> sa recharge — ne pouvait rien rapporter : elle remplaçait sept
 * décréments par une consultation de table.
 *
 * <p>Le vrai coût est ailleurs. Ce tick sur huit fait deux <b>recherches de conteneur</b> — l'une
 * devant, l'autre au-dessus — et chacune consulte l'état du bloc, son bloc-entité, et au besoin
 * balaie les entités de la case. Puis il déplace <b>un seul objet</b>.
 *
 * <p>Soixante-quatre cailloux qui traversent un entonnoir, c'est donc soixante-quatre tours de
 * recherche pour soixante-quatre objets. Le déplacement lui-même — un test de fusion, une addition de
 * compteur — ne pèse rien à côté.
 *
 * <h2>Ce que le lot change, et ce qu'il ne change pas</h2>
 *
 * <p>On sort la recherche de la boucle. Une recherche, seize objets, puis une recharge de
 * <b>huit fois seize</b> ticks. Le débit moyen est <em>exactement</em> celui de vanilla — seize
 * objets par cent-vingt-huit ticks est la même chose qu'un objet par huit ticks — et le nombre de
 * recherches est divisé par seize.
 *
 * <p>Ce n'est pas un compromis déguisé. Un entonnoir ralenti transfère moins ; celui-ci transfère
 * autant, moins souvent. Sur un lot fini il finit même <em>plus tôt</em> : les soixante-quatre
 * cailloux sont partis au tick 384 au lieu du tick 512, parce que la dernière recharge n'a plus rien
 * à retenir.
 *
 * <h2>L'argument qui rend le procédé légitime : vanilla le fait déjà</h2>
 *
 * <p>Ce n'est pas une invention de ce mod. Quand un entonnoir ramasse un objet <b>tombé au sol</b>,
 * le jeu ne prend pas une unité :
 *
 * <pre>
 * public static boolean addItem(Container container, ItemEntity entity) {
 *     ItemStack copy = entity.getItem().copy();          // la pile entière
 *     ItemStack result = addItem(null, container, copy, null);
 * </pre>
 *
 * <p>Une pile de soixante-quatre entre d'un seul coup, pour la même recharge de huit ticks. La règle
 * « un objet à la fois » n'est donc pas une loi du jeu : c'est une particularité du transfert entre
 * conteneurs, et vanilla lui-même l'enfreint dès qu'il ramasse au sol. On étend la règle que le jeu
 * s'applique déjà, en payant la recharge que le ramassage, lui, ne paie pas.
 *
 * <h2>Pourquoi seize, et pas soixante-quatre</h2>
 *
 * <p>Le gain croît avec la taille du lot, et l'irrégularité aussi. À soixante-quatre, un entonnoir
 * reste sur une recharge de cinq cent douze ticks — vingt-cinq secondes pendant lesquelles un joueur
 * qui dépose un objet ne verrait rien bouger. Seize plafonne l'attente à six secondes et emporte déjà
 * l'essentiel du gain : la recherche est divisée par seize, et ce qui reste ne se divise plus.
 *
 * <p>Le premier transfert, lui, n'est jamais retardé : un entonnoir au repos a une recharge nulle, il
 * part immédiatement, et ne se recharge qu'ensuite.
 *
 * <h2>Le garde-fou que vanilla nous offre</h2>
 *
 * <p>Un lot pose une recharge supérieure à huit, ce que le jeu appelle une <b>recharge
 * personnalisée</b> :
 *
 * <pre>
 * if (wasEmpty &amp;&amp; container instanceof HopperBlockEntity h &amp;&amp; !h.isOnCustomCooldown()) {
 *     h.setCooldown(8 - skipTickCount);
 * }
 * </pre>
 *
 * <p>Vanilla refuse de réinitialiser à huit une recharge qu'il n'a pas posée. C'est précisément ce
 * qu'il nous faut : sans cette clause, un entonnoir qui vient de déplacer seize objets — et qui doit
 * donc cent-vingt-huit ticks — verrait sa dette effacée par l'arrivée de l'objet suivant, et
 * transférerait seize fois trop vite. Le débit serait multiplié par seize au lieu d'être préservé.
 *
 * <p>Le garde-fou n'a pas été ajouté pour nous, mais il tient exactement le bon rôle, et il est
 * <b>indispensable</b> : sans lui, ce module serait une duplication de débit.
 */
public final class Bulk {
    /**
     * Objets déplacés au plus en un seul transfert.
     *
     * <p>Voir la justification ci-dessus : seize emporte le gain sans rendre l'entonnoir apathique.
     */
    public static final int LOT = 16;

    /** Recharge vanilla pour un transfert, en ticks. */
    public static final int SPEED = 8;

    /** Objets sortis vers l'avant durant le tick courant. */
    private static int ejected;
    /** Objets aspirés depuis le dessus durant le tick courant. */
    private static int sucked;

    // Compteurs de rapport : transferts effectués, et objets déplacés. AtomicLong et non un long
    // brut — contrairement a ejected/sucked ci-dessus (lus et ecrits dans le meme tick serveur,
    // jamais partages entre threads), CEUX-CI sont ecrits depuis le thread du serveur integre a
    // chaque transfert mais lus depuis Radiographie.report() sur le thread de rendu (ou le thread
    // du crochet d'arret JVM) une fois la session terminee : sans visibilite garantie entre
    // threads, ce releve final pourrait lire une valeur perimee. Meme choix que Grele.TRIMMED_CALLS
    // et Reflet.SKIPPED, pour la meme raison.
    private static final AtomicLong TRANSFERS = new AtomicLong();
    private static final AtomicLong ITEMS = new AtomicLong();

    private Bulk() {}

    /** Remet à zéro le relevé du tick. Appelé à l'entrée du tick d'un entonnoir. */
    public static void beginTick() {
        ejected = 0;
        sucked = 0;
    }

    /**
     * Ce que la recharge doit valoir, après le transfert qui vient d'avoir lieu.
     *
     * <h2>Pourquoi le plus grand des deux, et non leur somme</h2>
     *
     * <p>Un tick d'entonnoir vanilla fait <b>deux</b> choses pour une seule recharge : il pousse un
     * objet devant, et il en aspire un au-dessus. Deux objets bougent, et la recharge reste de huit
     * ticks.
     *
     * <p>Le lot doit donc payer non pas le nombre d'objets, mais le nombre de <b>tours vanilla</b>
     * qu'il a remplacés. Seize poussés et quatre aspirés, c'est seize tours — les quatre aspirations
     * auraient tenu dans les seize, comme elles le font en vanilla. C'est le plus grand des deux, et
     * leur somme serait une double facturation.
     *
     * <p>L'appel <b>consomme</b> le relevé. Un entonnoir peut passer par la recharge sans être passé
     * par ce module — le ramassage d'un objet tombé au sol emprunte un autre chemin — et il paierait
     * alors la dette du transfert précédent. Un compteur qu'on ne remet pas à zéro finit toujours par
     * répondre à une question qu'on ne lui a pas posée.
     */
    public static int owed() {
        int turns = Math.max(ejected, sucked);
        ejected = 0;
        sucked = 0;
        return Math.max(1, turns);
    }

    /**
     * La recharge à payer pour le lot qui vient de partir — {@link #owed} tours vanilla, divisés par
     * {@link Settings#bulkSpeed()}.
     *
     * <h2>Ceci n'est PAS le reste de cette classe</h2>
     *
     * <p>Tout ce qui précède préserve le débit vanilla AU BIT PRÈS — c'est tout l'argument de cette
     * classe, répété trois fois dans sa Javadoc. {@code Settings#bulkSpeed()} rompt volontairement
     * cette promesse : demandé en toutes lettres par le patron du projet (des Trieurs perçus comme
     * « giga lents » en usage réel, malgré ce module), pas une dérive accidentelle. Voir {@code
     * Config#BULK_SPEED} pour l'assumer avec les mêmes chiffres qu'ici.
     *
     * <p>À vitesse 1 (le plancher), le résultat est <b>identique</b> à {@code ticks * owed()} — cette
     * méthode ne change donc rien pour qui n'a jamais touché le réglage.
     *
     * <h2>Le plancher d'un tick</h2>
     *
     * <p>Le jeu ne connaît pas de recharge négative ni fractionnaire. Un tout petit lot (un seul
     * objet, {@code owed() == 1}) à une vitesse élevée peut demander moins d'un tick — la division
     * entière l'arrondirait à zéro, ce qu'aucun entonnoir vanilla ne fait jamais. Le plancher à un
     * tick borne alors le multiplicateur RÉEL en dessous de la valeur demandée pour ce lot précis :
     * une limite du jeu, pas un défaut de cette méthode.
     */
    public static int cooldownFor(int vanillaTicks) {
        return Math.max(1, vanillaTicks * owed() / Settings.bulkSpeed());
    }

    /**
     * Le conteneur de destination est-il plein par cette face ?
     *
     * <p>Recopie de {@code isFullContainer}, qui est privé. Vanilla s'en sert comme sortie rapide
     * avant la boucle, et c'est exactement le cas qu'il faut garder rapide : un entonnoir
     * <b>bloqué</b> ne pose aucune recharge, donc il repasse ici <em>à chaque tick</em>. C'est son
     * état le plus coûteux, et le plus fréquent dans une salle de stockage.
     */
    public static boolean isFull(Container container, Direction side) {
        if (container instanceof WorldlyContainer worldly) {
            for (int slot : worldly.getSlotsForFace(side)) {
                ItemStack stack = container.getItem(slot);
                if (stack.getCount() < stack.getMaxStackSize()) {
                    return false;
                }
            }
            return true;
        }
        int size = container.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    /** Le lot à prendre dans cette pile : jamais plus qu'elle ne contient, jamais plus que le plafond. */
    public static int sizeFor(ItemStack source) {
        return Math.min(source.getCount(), LOT);
    }

    /**
     * Sort un lot vers le conteneur de devant.
     *
     * <p>La recherche du conteneur a déjà eu lieu, une fois, chez l'appelant. Ici l'on ne fait que
     * déplacer — c'est tout l'objet du module.
     *
     * @return vrai si au moins un objet est parti
     */
    public static boolean eject(HopperBlockEntity self, Container into, Direction side) {
        for (int slot = 0; slot < self.getContainerSize(); slot++) {
            ItemStack source = self.getItem(slot);
            if (source.isEmpty()) {
                continue;
            }
            int moved = move(self, into, slot, sizeFor(source), side);
            if (moved > 0) {
                ejected += moved;
                into.setChanged();
                note(moved);
                return true;
            }
        }
        return false;
    }

    /**
     * Aspire un lot depuis le conteneur du dessus, emplacement par emplacement.
     *
     * @return vrai si au moins un objet est arrivé
     */
    public static boolean suck(Hopper hopper, Container from, int slot, Direction side) {
        ItemStack source = from.getItem(slot);
        if (source.isEmpty() || !mayTake(hopper, from, source, slot, side)) {
            return false;
        }
        // La face ne sert qu'au test de prélèvement, ci-dessus. L'insertion dans l'entonnoir se fait
        // sans face — c'est ce que vanilla passe à addItem dans tryTakeInItemFromSlot.
        int moved = move(from, hopper, slot, sizeFor(source), null);
        if (moved <= 0) {
            return false;
        }
        sucked += moved;
        from.setChanged();
        note(moved);
        return true;
    }

    /**
     * Le déplacement lui-même, et la remise en place de ce qui n'a pas pu passer.
     *
     * <h2>Le reliquat, et pourquoi vanilla ne sait pas le traiter</h2>
     *
     * <p>Vanilla ne prend jamais qu'une unité, et son rattrapage en tient compte :
     *
     * <pre>
     * itemStack.setCount(originalCount);
     * if (originalCount == 1) self.setItem(slot, itemStack);
     * </pre>
     *
     * <p>Avec une unité, l'insertion réussit entièrement ou échoue entièrement — il n'y a pas de
     * reliquat, et remettre le compte d'origine suffit. Avec un lot, l'insertion peut être
     * <b>partielle</b>, et ce code remettrait le compte entier <em>en plus</em> de ce qui est déjà
     * parti : une duplication d'objets.
     *
     * <p>C'est pour cela que ce module ne peut pas se contenter de changer un « 1 » en « 16 » dans le
     * code du jeu, et qu'il refait le chemin en entier. Ici, ce qui reste est rendu à son emplacement,
     * et le compte déplacé est la différence — jamais une supposition.
     *
     * @return le nombre d'objets réellement déplacés
     */
    private static int move(Container from, Container into, int slot, int wanted, Direction side) {
        ItemStack taken = from.removeItem(slot, wanted);
        if (taken.isEmpty()) {
            return 0;
        }
        int held = taken.getCount();
        ItemStack left = HopperBlockEntity.addItem(from, into, taken, side);
        int moved = held - left.getCount();

        if (!left.isEmpty()) {
            ItemStack back = from.getItem(slot);
            if (back.isEmpty()) {
                from.setItem(slot, left);
            } else {
                // Même objet, mêmes composants : removeItem vient d'en détacher une partie. On ne
                // peut pas dépasser la pile, puisqu'on n'en a jamais pris plus qu'elle n'en avait.
                back.grow(left.getCount());
            }
        }
        return moved;
    }

    /**
     * Le conteneur source accepte-t-il qu'on lui prenne cet objet par cette face ?
     *
     * <p>Recopie de {@code canTakeItemFromContainer}, qui est privé. Les deux appels qu'elle fait sont
     * publics ; seule la méthode qui les assemble ne l'est pas.
     */
    private static boolean mayTake(Hopper into, Container from, ItemStack stack, int slot, Direction side) {
        if (!from.canTakeItem(into, slot, stack)) {
            return false;
        }
        return !(from instanceof WorldlyContainer worldly)
                || side == null
                || worldly.canTakeItemThroughFace(slot, stack, side);
    }

    private static void note(int moved) {
        TRANSFERS.incrementAndGet();
        ITEMS.addAndGet(moved);
    }

    /** Taille moyenne d'un lot, pour le rapport. Un contre vanilla, seize au mieux. */
    public static double averageLot() {
        long t = TRANSFERS.get();
        return t == 0L ? 0d : (double) ITEMS.get() / t;
    }

    public static long transfers() {
        return TRANSFERS.get();
    }

    public static long items() {
        return ITEMS.get();
    }

    /**
     * Un résumé lisible, même statut que {@link Grele#report()} : cumulé depuis le démarrage du
     * client (voir {@link #reset}, appelé uniquement par le banc {@code Kitchen}, jamais en jeu),
     * pas remis à zéro par une session de {@code Radiographie}.
     */
    public static String report() {
        long t = TRANSFERS.get();
        if (t == 0L) {
            return "bulk : aucun transfert par lot (reglage \"bulk\" eteint, ou aucun entonnoir "
                    + "actif depuis le demarrage)";
        }
        return String.format(Locale.ROOT,
                "bulk : %d transfert(s) par lot, %d objet(s) deplaces au total, lot moyen %.1f/%d",
                t, ITEMS.get(), averageLot(), LOT);
    }

    public static void reset() {
        TRANSFERS.set(0L);
        ITEMS.set(0L);
        ejected = 0;
        sucked = 0;
    }
}
