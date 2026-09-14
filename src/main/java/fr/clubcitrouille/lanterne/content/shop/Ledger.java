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

    public static final Codec<Ledger> CODEC = RecordCodecBuilder.create(spec -> spec.group(
            Account.CODEC.listOf().optionalFieldOf("comptes", List.of())
                    .forGetter(ledger -> new ArrayList<>(ledger.accounts.values())),
            Codec.unboundedMap(Identifier.CODEC, Codec.LONG).optionalFieldOf("volumes", Map.of())
                    .forGetter(ledger -> ledger.volumes)
    ).apply(spec, Ledger::new));

    public static final SavedDataType<Ledger> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, KEY), Ledger::new, CODEC);

    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Map<Identifier, Long> volumes = new HashMap<>();

    public Ledger() {
    }

    private Ledger(List<Account> loaded, Map<Identifier, Long> traded) {
        for (Account account : loaded) {
            this.accounts.put(account.id(), account);
        }
        this.volumes.putAll(traded);
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
     * @return {@code null} si tout va bien, ou la raison du refus
     */
    public String transfer(UUID from, String fromName, UUID to, String toName, long amount) {
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
        set(from, fromName, balance(from) - amount);
        set(to, toName, balance(to) + amount);
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

    /**
     * La détente : chaque volume se rapproche de zéro.
     *
     * <p>Le dernier pas retire <b>au moins une unité</b>. Sans ce détail, une division entière par un
     * facteur légèrement inférieur à un s'arrête sur les petits nombres : un volume de 8 avec six pour
     * cent de détente perdrait zéro unité à chaque fois, et l'article resterait décalé de quelques
     * millièmes pour l'éternité. Avec, tout volume finit à zéro exactement.
     */
    public void relax() {
        int permille = Tariff.relaxPermille();
        if (permille <= 0 || this.volumes.isEmpty()) {
            return;
        }
        var walk = this.volumes.entrySet().iterator();
        while (walk.hasNext()) {
            var entry = walk.next();
            long value = entry.getValue();
            long shed = Math.max(1L, Math.abs(value) * permille / 1000L);
            long next = value > 0L ? value - shed : value + shed;
            if (value > 0L && next < 0L || value < 0L && next > 0L || next == 0L) {
                walk.remove();
            } else {
                entry.setValue(next);
            }
        }
        setDirty();
    }
}
