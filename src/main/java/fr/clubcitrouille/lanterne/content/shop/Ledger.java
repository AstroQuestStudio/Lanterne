package fr.clubcitrouille.lanterne.content.shop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le grand livre : les soldes et les volumes échangés, attachés à la sauvegarde.
 *
 * <h2>L'argent n'est pas un objet</h2>
 *
 * <p>C'est la décision fondatrice de tout ce paquet, et elle mérite d'être écrite. Beaucoup de mods
 * d'économie représentent l'argent par un objet — un lingot, une pièce, un billet — que l'on empile
 * dans son inventaire. C'est plus <em>visible</em>, et c'est faux sur trois points :
 *
 * <ol>
 *   <li>on peut le <b>perdre</b> : une mort dans la lave et l'épargne d'un mois a disparu ;</li>
 *   <li>on peut le <b>dupliquer</b> : toute faille de duplication d'objets du jeu ou d'un autre mod
 *       devient instantanément une faille de duplication d'argent, et l'inflation est irréversible ;</li>
 *   <li>on ne peut pas le <b>plafonner</b> : un coffre de shulkers de pièces n'est pas une somme,
 *       c'est un volume, et personne ne sait combien il y a d'argent sur le serveur.</li>
 * </ol>
 *
 * <p>Ici, un solde est un {@code long} dans une {@link SavedData}, c'est-à-dire un nombre écrit dans
 * le fichier de sauvegarde du monde, en même temps que lui. Il ne traîne dans aucun inventaire, ne
 * tombe au sol, ne brûle pas, ne se duplique pas. La seule façon d'en créer est une commande
 * d'administrateur ou une vente à la boutique, et les deux passent par ce fichier.
 *
 * <h2>Un seul livre pour le serveur entier</h2>
 *
 * <p>Les données de monde de vanilla sont attachées à un {@code ServerLevel}. Un solde, lui,
 * n'appartient à aucune dimension : celui qui vend son fer dans le Nether veut l'acheter dans
 * l'Overworld. Le livre est donc rangé une fois pour toutes dans l'Overworld — le seul monde dont on
 * soit certain qu'il existe et qu'il se charge en premier. Même raisonnement, même endroit, que
 * {@code content.waypoint.Atlas}.
 *
 * <h2>Ce que contient aussi ce livre, et pourquoi au même endroit</h2>
 *
 * <p>Les <b>volumes échangés</b>, article par article, qui commandent la dérive des prix. On aurait pu
 * les ranger ailleurs ; les mettre ici garantit qu'un solde et le prix qui l'a produit sont écrits
 * dans le <em>même</em> enregistrement, donc sauvegardés ensemble ou pas du tout. Une sauvegarde où
 * les soldes seraient d'après la panne et les volumes d'avant serait une économie incohérente, et
 * personne ne s'en apercevrait avant des semaines.
 *
 * <p>Et, depuis les puits, les <b>recettes récentes</b> de chaque joueur — voir {@link Toll}. Même
 * raisonnement, poussé d'un cran : si les soldes survivaient à une panne mais pas les compteurs de
 * quota, un exploitant de ferme retrouverait sa franchise intacte à chaque redémarrage du serveur, et
 * il suffirait de demander un redémarrage pour annuler le dispositif entier. Les trois choses sont
 * dans le même enregistrement, donc écrites ensemble ou pas du tout.
 */
public class Ledger extends SavedData {
    private static final String KEY = "lanterne_ledger";

    /**
     * Un compte.
     *
     * <p>Le nom est recopié à chaque connexion et ne sert qu'à l'affichage du classement : c'est
     * l'{@code UUID} qui identifie, jamais le nom. Un joueur qui change de pseudonyme garde son
     * argent, et deux joueurs de même pseudonyme sur deux comptes différents ne se mélangent pas.
     */
    public record Account(UUID id, String name, long amount) {
        public static final Codec<Account> CODEC = RecordCodecBuilder.create(spec -> spec.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(Account::id),
                Codec.STRING.optionalFieldOf("nom", "?").forGetter(Account::name),
                Codec.LONG.fieldOf("solde").forGetter(Account::amount)
        ).apply(spec, Account::new));
    }

    /**
     * Les recettes récentes d'un joueur : en tout, et article par article.
     *
     * <p>Ce sont les compteurs que {@link Toll} consulte. Ils sont en <b>centimes</b>, ils suivent le
     * cours du marché et non ce qui a été versé — voir {@link Toll} pour la raison —, et ils
     * redescendent d'eux-mêmes à chaque détente.
     *
     * <p>Un joueur dont tous les compteurs sont revenus à zéro est <b>effacé de la carte</b> plutôt
     * que gardé à zéro : sur un serveur qui vit deux ans, garder une ligne par joueur et par article
     * jamais nettoyée ferait grossir la sauvegarde sans fin, pour décrire un état qui est exactement
     * celui du joueur qui n'a jamais rien vendu.
     *
     * @param who     le joueur
     * @param total   ses recettes récentes, tous articles confondus, en centimes
     * @param perItem ses recettes récentes article par article, en centimes
     */
    public record Takings(UUID who, long total, Map<Identifier, Long> perItem) {
        public static final Codec<Takings> CODEC = RecordCodecBuilder.create(spec -> spec.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(Takings::who),
                Codec.LONG.optionalFieldOf("total", 0L).forGetter(Takings::total),
                Codec.unboundedMap(Identifier.CODEC, Codec.LONG)
                        .optionalFieldOf("articles", Map.of()).forGetter(Takings::perItem)
        ).apply(spec, Takings::new));
    }

    public static final Codec<Ledger> CODEC = RecordCodecBuilder.create(spec -> spec.group(
            Account.CODEC.listOf().optionalFieldOf("comptes", List.of())
                    .forGetter(ledger -> new ArrayList<>(ledger.accounts.values())),
            Codec.unboundedMap(Identifier.CODEC, Codec.LONG).optionalFieldOf("volumes", Map.of())
                    .forGetter(ledger -> ledger.volumes),
            Takings.CODEC.listOf().optionalFieldOf("recettes", List.of())
                    .forGetter(Ledger::takingsList),
            Codec.LONG.optionalFieldOf("retenu", 0L).forGetter(ledger -> ledger.withheld)
    ).apply(spec, Ledger::new));

    public static final SavedDataType<Ledger> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, KEY), Ledger::new, CODEC);

    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Map<Identifier, Long> volumes = new HashMap<>();

    /** Les recettes récentes, par joueur. Absent de la carte = n'a rien vendu récemment. */
    private final Map<UUID, Map<Identifier, Long>> takings = new HashMap<>();
    private final Map<UUID, Long> totals = new HashMap<>();

    /** Le cumul de ce que les puits n'ont jamais versé. Une statistique, jamais un solde. */
    private long withheld;

    public Ledger() {
    }

    private Ledger(List<Account> loaded, Map<Identifier, Long> traded, List<Takings> recent,
            long neverPaid) {
        for (Account account : loaded) {
            this.accounts.put(account.id(), account);
        }
        this.volumes.putAll(traded);
        for (Takings takings : recent) {
            if (takings.total() > 0L) {
                this.totals.put(takings.who(), takings.total());
            }
            if (!takings.perItem().isEmpty()) {
                this.takings.put(takings.who(), new HashMap<>(takings.perItem()));
            }
        }
        this.withheld = Math.max(0L, neverPaid);
    }

    private List<Takings> takingsList() {
        List<Takings> out = new ArrayList<>(this.totals.size());
        for (Map.Entry<UUID, Long> entry : this.totals.entrySet()) {
            out.add(new Takings(entry.getKey(), entry.getValue(),
                    this.takings.getOrDefault(entry.getKey(), Map.of())));
        }
        return out;
    }

    /** Le livre du serveur. Toujours celui de l'Overworld — voir l'en-tête. */
    public static Ledger of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // --- Les soldes --------------------------------------------------------

    /**
     * Le solde de ce joueur, en centimes.
     *
     * <p>Un joueur sans compte vaut le solde de départ, et non zéro : c'est ce qui permet de ne créer
     * le compte qu'à la première connexion sans que la boutique ait à traiter le cas « pas encore de
     * compte » à chaque consultation.
     */
    public long balance(UUID who) {
        Account account = this.accounts.get(who);
        return account == null ? Tariff.start() : account.amount();
    }

    public boolean known(UUID who) {
        return this.accounts.containsKey(who);
    }

    /**
     * Ouvre le compte s'il n'existe pas, et rafraîchit le nom.
     *
     * <p>Appelée à chaque connexion. L'ouverture ne crédite le solde de départ qu'<b>une fois</b> : un
     * joueur ruiné qui se déconnecte et revient retrouve sa ruine, ce qui est la moitié de l'intérêt
     * d'une économie.
     */
    public void seat(ServerPlayer player) {
        UUID who = player.getUUID();
        String name = player.getGameProfile().name();
        Account current = this.accounts.get(who);
        if (current == null) {
            this.accounts.put(who, new Account(who, name, clamp(Tariff.start())));
            setDirty();
            return;
        }
        if (!current.name().equals(name)) {
            this.accounts.put(who, new Account(who, name, current.amount()));
            setDirty();
        }
    }

    /** Fixe un solde. Le nom n'est écrasé que s'il est connu. */
    public void set(UUID who, String name, long amount) {
        Account current = this.accounts.get(who);
        String kept = name != null && !name.isBlank() ? name : current != null ? current.name() : "?";
        this.accounts.put(who, new Account(who, kept, clamp(amount)));
        setDirty();
    }

    /**
     * Ajoute au solde, dans la limite du plafond.
     *
     * @return ce qui a réellement été crédité — inférieur au demandé si le plafond a été touché
     */
    public long credit(UUID who, String name, long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        long before = balance(who);
        long after = clamp(before + amount);
        set(who, name, after);
        return after - before;
    }

    /**
     * Retire du solde, si et seulement si le compte le permet.
     *
     * <p>Le tout ou rien est la règle : un débit partiel laisserait le joueur avec la moitié d'un
     * achat payé et rien reçu, et c'est exactement l'incohérence que la caisse existe pour interdire.
     *
     * @return vrai si le débit a eu lieu
     */
    public boolean debit(UUID who, String name, long amount) {
        if (amount < 0L) {
            return false;
        }
        long before = balance(who);
        if (before < amount) {
            return false;
        }
        set(who, name, before - amount);
        return true;
    }

    /**
     * Un virement entre deux comptes.
     *
     * <p>Les deux vérifications ont lieu <b>avant</b> tout mouvement : que l'émetteur ait la somme, et
     * que le destinataire puisse la recevoir sans toucher le plafond. Faute de quoi un virement vers
     * un compte presque plein détruirait la différence, et l'argent disparu ne se retrouve jamais.
     *
     * <p>Les <b>frais</b> sont prélevés sur ce qui arrive, non sur ce qui part : l'émetteur est
     * débité exactement du montant qu'il a tapé, et le destinataire reçoit ce montant moins les
     * frais. C'est le seul ordre qui ne surprenne personne — celui qui écrit « 100 » veut voir 100
     * quitter son compte. Les frais sont nuls par défaut ; voir {@link Toll#fee}.
     *
     * @param fee ce que le virement coûte, en centimes ; jamais supérieur au montant
     * @return {@code null} si tout va bien, ou la raison du refus
     */
    public String transfer(UUID from, String fromName, UUID to, String toName, long amount,
            long fee) {
        if (amount <= 0L) {
            return "Un virement doit être strictement positif.";
        }
        if (from.equals(to)) {
            return "Se virer de l'argent à soi-même ne changerait rien.";
        }
        if (balance(from) < amount) {
            return "Il te manque " + Coin.say(amount - balance(from)) + ".";
        }
        if (balance(to) > Coin.CEILING - amount) {
            return "Le destinataire ne peut pas recevoir autant : son compte est presque plein.";
        }
        long kept = Math.max(0L, Math.min(amount, fee));
        set(from, fromName, balance(from) - amount);
        set(to, toName, balance(to) + amount - kept);
        withhold(kept);
        return null;
    }

    /** Les comptes les plus fournis, du plus riche au moins riche. */
    public List<Account> richest(int howMany) {
        List<Account> all = new ArrayList<>(this.accounts.values());
        all.sort(Comparator.comparingLong(Account::amount).reversed()
                .thenComparing(Account::name, String.CASE_INSENSITIVE_ORDER));
        return all.subList(0, Math.min(howMany, all.size()));
    }

    public int accountCount() {
        return this.accounts.size();
    }

    /** La masse monétaire du serveur : la seule façon de voir venir une inflation. */
    public long mass() {
        long total = 0L;
        for (Account account : this.accounts.values()) {
            total += account.amount();
        }
        return total;
    }

    private static long clamp(long amount) {
        return Math.max(0L, Math.min(Coin.CEILING, amount));
    }

    // --- Les volumes -------------------------------------------------------

    /** Le volume net d'un article : les unités achetées, moins celles qui ont été revendues. */
    public long volume(Identifier item) {
        Long known = this.volumes.get(item);
        return known == null ? 0L : known;
    }

    /**
     * Enregistre un échange.
     *
     * <p>Le volume est borné à cent fois l'élasticité. Au-delà, {@code tanh} rend déjà un résultat
     * indiscernable de sa limite, et laisser le nombre croître sans fin ne ferait qu'allonger le temps
     * que met la détente à ramener le prix vers son ancre — une année de commerce deviendrait
     * irréversible.
     */
    public void trade(Identifier item, long units) {
        if (units == 0L) {
            return;
        }
        long cap = 100L * Tariff.elasticity();
        long next = Math.max(-cap, Math.min(cap, volume(item) + units));
        if (next == 0L) {
            this.volumes.remove(item);
        } else {
            this.volumes.put(item, next);
        }
        setDirty();
    }

    /** Remet un article à son prix d'ancrage. */
    public void forget(Identifier item) {
        if (this.volumes.remove(item) != null) {
            setDirty();
        }
    }

    /** Remet tout le marché à ses prix d'ancrage. */
    public int forgetAll() {
        int count = this.volumes.size();
        this.volumes.clear();
        setDirty();
        return count;
    }

    public int driftedCount() {
        return this.volumes.size();
    }

    // --- Les recettes récentes, qui commandent les puits -------------------

    /** Les recettes récentes de ce joueur, tous articles confondus, en centimes. */
    public long takings(UUID who) {
        Long known = this.totals.get(who);
        return known == null ? 0L : known;
    }

    /** Les recettes récentes de ce joueur sur cet article, en centimes. */
    public long takings(UUID who, Identifier item) {
        Map<Identifier, Long> mine = this.takings.get(who);
        if (mine == null) {
            return 0L;
        }
        Long known = mine.get(item);
        return known == null ? 0L : known;
    }

    /**
     * Enregistre une recette.
     *
     * <p>Le montant passé est le <b>brut</b> — le cours du marché multiplié par les unités vendues —,
     * jamais le net. La raison est dans {@link Toll} : faire suivre le net créerait une boucle de
     * retour qui ramollit la retenue au moment précis où elle devrait serrer.
     *
     * <p>Les compteurs sont bornés à cent fois la plus grande des deux franchises. Au-delà,
     * {@code tanh} rend déjà sa limite, et laisser le nombre croître ne ferait qu'allonger le temps
     * que met la détente à le ramener sous la franchise — un mois de ferme deviendrait une peine à
     * perpétuité, ce qui n'est pas le contrat.
     */
    public void earn(UUID who, Identifier item, long gross) {
        if (gross <= 0L || who == null) {
            return;
        }
        long cap = 100L * Math.max(1L, Math.max(Tariff.quotaAllowance(), Tariff.debitAllowance()));
        this.totals.merge(who, gross, (before, add) -> Math.min(cap, before + add));
        this.takings.computeIfAbsent(who, key -> new HashMap<>())
                .merge(item, gross, (before, add) -> Math.min(cap, before + add));
        setDirty();
    }

    /** Remet à neuf les compteurs de puits d'un joueur. Réservé à l'administration. */
    public boolean pardon(UUID who) {
        boolean something = this.totals.remove(who) != null;
        something |= this.takings.remove(who) != null;
        if (something) {
            setDirty();
        }
        return something;
    }

    /** Le cumul de ce que les puits n'ont jamais versé, en centimes. */
    public long withheld() {
        return this.withheld;
    }

    /** Ajoute au cumul des retenues. C'est une statistique : aucun compte n'est mouvementé. */
    public void withhold(long cents) {
        if (cents <= 0L) {
            return;
        }
        this.withheld = Math.min(Coin.CEILING, this.withheld + cents);
        setDirty();
    }

    /** Les articles sur lesquels ce joueur a entamé sa franchise, du plus entamé au moins. */
    public List<Map.Entry<Identifier, Long>> biggestTakings(UUID who, int howMany) {
        Map<Identifier, Long> mine = this.takings.get(who);
        if (mine == null || mine.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<Identifier, Long>> all = new ArrayList<>(mine.entrySet());
        all.sort(Map.Entry.<Identifier, Long>comparingByValue().reversed());
        return all.subList(0, Math.min(howMany, all.size()));
    }

    // --- La détente --------------------------------------------------------

    /**
     * La détente : chaque volume, et chaque compteur de puits, se rapproche de zéro.
     *
     * <p>Le dernier pas retire <b>au moins une unité</b>. Sans ce détail, une division entière par un
     * facteur légèrement inférieur à un s'arrête sur les petits nombres : un volume de 8 avec six pour
     * cent de détente perdrait zéro unité à chaque fois, et l'article resterait décalé de quelques
     * millièmes pour l'éternité. Avec, tout volume finit à zéro exactement.
     *
     * <p><b>Deux rythmes, et c'est voulu.</b> Les volumes s'effacent à soixante pour mille toutes les
     * cinq minutes — une demi-vie d'une heure —, parce qu'un prix doit revenir vite vers son ancre.
     * Les compteurs de puits s'effacent à deux pour mille — une demi-vie de vingt-neuf heures —,
     * parce qu'une franchise qui se reconstitue entre deux soirées n'est pas une franchise : le
     * balayage montre qu'à vingt pour mille le dispositif entier devient invisible pour qui joue tous
     * les jours, c'est-à-dire pour celui qu'il vise.
     */
    public void relax() {
        boolean touched = fade(this.volumes, Tariff.relaxPermille());
        int slow = Tariff.tollRelaxPermille();
        if (slow > 0) {
            var walk = this.takings.entrySet().iterator();
            while (walk.hasNext()) {
                var entry = walk.next();
                touched |= fade(entry.getValue(), slow);
                if (entry.getValue().isEmpty()) {
                    walk.remove();
                    touched = true;
                }
            }
            var totalsWalk = this.totals.entrySet().iterator();
            while (totalsWalk.hasNext()) {
                var entry = totalsWalk.next();
                long next = wane(entry.getValue(), slow);
                if (next == 0L) {
                    totalsWalk.remove();
                } else {
                    entry.setValue(next);
                }
                touched = true;
            }
        }
        if (touched) {
            setDirty();
        }
    }

    /** Rapproche de zéro toutes les valeurs d'une carte, et retire celles qui l'atteignent. */
    private static <K> boolean fade(Map<K, Long> map, int permille) {
        if (permille <= 0 || map.isEmpty()) {
            return false;
        }
        var walk = map.entrySet().iterator();
        while (walk.hasNext()) {
            var entry = walk.next();
            long next = wane(entry.getValue(), permille);
            if (next == 0L) {
                walk.remove();
            } else {
                entry.setValue(next);
            }
        }
        return true;
    }

    /** Un pas de détente sur un nombre signé. Zéro est atteint, jamais dépassé. */
    private static long wane(long value, int permille) {
        if (value == 0L) {
            return 0L;
        }
        long shed = Math.max(1L, Math.abs(value) * permille / 1000L);
        long next = value > 0L ? value - shed : value + shed;
        return value > 0L && next < 0L || value < 0L && next > 0L ? 0L : next;
    }
}
