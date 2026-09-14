package fr.clubcitrouille.lanterne.content.disc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import java.util.Map;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.painting.Studio;

/**
 * Les sillons : des emplacements de disque réservés à l'avance, et pourquoi il en fallait.
 *
 * <h2>Le mur contre lequel butent tous les mods de disques personnalisés</h2>
 *
 * <p>Un disque de Minecraft se joue parce qu'une entrée existe dans le registre
 * {@code jukebox_song}. Ce registre est un <b>registre de données</b> : il est bâti au chargement du
 * monde à partir des paquets de données, puis scellé. On ne peut pas y ajouter une entrée pendant la
 * partie.
 *
 * <p>La solution qui vient à l'esprit — forcer un rechargement des données après chaque gravure — a
 * été examinée en détail, et elle est <b>dangereuse</b>. Un rechargement reconstruit le registre, donc
 * tous ses {@code Holder}. Les disques déjà posés dans les inventaires gardent, eux, les anciens.
 * Or {@code RegistryFixedCodec.encode} refuse d'écrire un porteur qui n'appartient pas au registre
 * courant ({@code Holder.canSerializeIn}), et le composant serait donc <b>perdu à la sauvegarde</b> :
 * tous les disques du serveur redeviendraient muets. Un mod qui vide les inventaires de ses joueurs
 * pour économiser une indirection n'a pas le droit d'exister.
 *
 * <p>D'où ce fichier. Le mod déclare au chargement un jeu de <b>sillons vides</b> —
 * {@code lanterne:grave_00}, {@code grave_01}, … — qui existent dans le registre dès le premier
 * instant. Graver n'ajoute rien : cela <em>occupe</em> un sillon déjà là. Aucun rechargement, aucun
 * porteur périmé, aucune perte.
 *
 * <h2>Pourquoi les octets peuvent changer sans rechargement</h2>
 *
 * <p>Un sillon désigne un son, qui désigne un fichier. Ce fichier n'est ouvert qu'<b>au moment où le
 * morceau se joue</b> — {@code Reel.getResource} rend un flux paresseux, et le mode « stream » de
 * Minecraft lit au fur et à mesure. Changer le contenu du fichier change donc ce qu'on entend, sans
 * que le gestionnaire de ressources ait à relire quoi que ce soit.
 *
 * <p>C'est l'autre moitié de l'astuce, et elle n'aurait pas marché avec le mode « chargé en mémoire » :
 * celui-là garde un tampon décodé et ne le relit jamais.
 *
 * <h2>La durée, seul compromis de tout le mécanisme</h2>
 *
 * <p>Un sillon déclare sa durée une fois pour toutes, et le jukebox s'arrête à cette durée. Un sillon
 * de quinze minutes occupé par un morceau de trois laisserait donc tourner douze minutes de silence.
 *
 * <p>Les durées sont donc réparties <b>géométriquement</b> entre {@link #FLOOR} et la durée maximale
 * réglée : chaque sillon est environ six pour cent plus long que le précédent, et la gravure prend le
 * plus petit sillon libre assez long. Le silence résiduel vaut au pire ces six pour cent — onze
 * secondes sur un morceau de trois minutes — au lieu de douze minutes. C'est un défaut réel, mesuré,
 * et c'est le prix de l'absence de rechargement. Il diminue encore si l'on augmente le nombre de
 * sillons.
 */
public final class Slots {
    /** Durée du plus petit sillon, en secondes. */
    public static final int FLOOR = 15;

    /** Ce qu'un sillon contient, ou rien. */
    public record Cut(String hash, String title, float seconds, String author) {}

    /**
     * Ce que le SERVEUR a gravé. C'est la seule table qui fasse foi, et la seule qu'on écrive.
     *
     * <p>Concurrente : lue par le fil réseau, écrite par le fil du serveur.
     */
    private static final Map<Integer, Cut> TAKEN = new ConcurrentHashMap<>();

    /**
     * Ce que le CLIENT sait des sillons, d'après le catalogue reçu.
     *
     * <h2>Pourquoi deux tables, et ce qu'une seule a coûté</h2>
     *
     * <p>En partie solo, le client et le serveur vivent dans la même machine virtuelle : ils
     * partagent donc les champs statiques. La première version n'avait qu'une table, et le client la
     * vidait en se déconnectant — juste avant que le serveur ne l'écrive sur le disque à l'extinction.
     * Résultat : {@code sillons.properties} réécrit <b>vide</b>, et toutes les gravures perdues au
     * redémarrage. Le journal du commanditaire porte les deux horodatages à la seconde près.
     *
     * <p>Les deux tables ne se recouvrent jamais : sur un serveur dédié, {@link #SEEN} reste vide ;
     * sur un client en multijoueur, {@link #TAKEN} reste vide ; en solo les deux disent la même
     * chose. Aucune ne peut plus effacer l'autre, et c'est le genre de bogue qu'on ne corrige pas
     * deux fois.
     */
    private static final Map<Integer, Cut> SEEN = new ConcurrentHashMap<>();

    private Slots() {}

    /** Le nom d'un sillon, tel qu'il apparaît dans le registre et dans les ressources. */
    public static String name(int slot) {
        return String.format(Locale.ROOT, "grave_%02d", slot);
    }

    /**
     * La durée déclarée d'un sillon, en secondes.
     *
     * <p>Répartition géométrique : {@code FLOOR * (max/FLOOR)^(i/(n-1))}. Une répartition linéaire
     * aurait donné des sillons courts trop espacés — quinze, trente, quarante-cinq secondes — là où
     * se trouvent justement la plupart des morceaux qu'on grave.
     */
    public static float length(int slot) {
        int count = Math.max(2, Studio.discSlots());
        double max = Math.max(FLOOR + 1, Studio.discMaxSeconds());
        double ratio = Math.pow(max / FLOOR, slot / (double) (count - 1));
        return (float) Math.min(max, Math.ceil(FLOOR * ratio));
    }

    /**
     * Le plus petit sillon libre assez long pour ce morceau.
     *
     * <p>Rend {@code -1} si aucun ne convient : soit ils sont tous pris, soit le morceau dépasse la
     * durée maximale — et ce second cas est déjà refusé plus tôt, avec un meilleur message.
     */
    public static int vacant(float seconds) {
        int count = Studio.discSlots();
        for (int slot = 0; slot < count; slot++) {
            if (!TAKEN.containsKey(slot) && length(slot) >= seconds) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Ce que porte ce sillon, vu d'où l'on est.
     *
     * <p>La table du serveur d'abord : elle fait foi quand elle existe. Celle du client ensuite,
     * qui est la seule à être remplie sur une machine qui n'héberge rien.
     */
    public static @Nullable Cut at(int slot) {
        Cut cut = TAKEN.get(slot);
        return cut != null ? cut : SEEN.get(slot);
    }

    /** L'empreinte du morceau qui occupe ce sillon, ou une chaîne vide. */
    public static String hashAt(int slot) {
        Cut cut = at(slot);
        return cut == null ? "" : cut.hash();
    }

    /** Le sillon qui porte déjà cette empreinte, ou moins un. Voir {@code Groove.engrave}. */
    public static int holding(String hash) {
        for (Map.Entry<Integer, Cut> entry : TAKEN.entrySet()) {
            if (entry.getValue().hash().equals(hash)) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /** Le client note ce que le serveur lui annonce. N'écrit jamais sur le disque. */
    public static void mirror(int slot, Cut cut) {
        SEEN.put(slot, cut);
    }

    /** Le client oublie ce qu'il savait — déconnexion. La table du serveur n'est pas touchée. */
    public static void forgetMirror() {
        SEEN.clear();
    }

    public static void occupy(int slot, Cut cut) {
        TAKEN.put(slot, cut);
    }

    /** Libère un sillon — ce que fait l'effacement d'un disque. */
    public static void release(int slot) {
        TAKEN.remove(slot);
    }

    public static int used() {
        return TAKEN.size();
    }

    /** Ce que le serveur a gravé. Pour le catalogue, les commandes et l'onglet créatif. */
    public static Map<Integer, Cut> all() {
        return TAKEN;
    }

    // --- Persistance -------------------------------------------------------

    private static Path ledger() {
        return Groove.cacheFolder().resolve("sillons.properties");
    }

    /**
     * Relit l'occupation des sillons.
     *
     * <p>Un format lisible à l'œil, comme le pont d'héritage des tableaux : c'est le seul endroit où
     * un administrateur peut voir quel sillon porte quoi, et libérer à la main celui d'un morceau
     * qu'il ne veut plus. Le fichier est petit — une ligne par disque gravé.
     */
    public static void recall() {
        TAKEN.clear();
        Path file = ledger();
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException problem) {
            Lanterne.LOG.warn("[ATELIER] registre des sillons illisible : {}", problem.getMessage());
            return;
        }
        for (String key : properties.stringPropertyNames()) {
            // Format : <sillon> = <empreinte>|<secondes>|<auteur>|<titre>
            // Le titre en dernier, parce que c'est le seul champ qui peut contenir n'importe quoi —
            // découper sur les trois premières barres suffit alors, et un titre avec une barre
            // verticale ne casse rien.
            String[] parts = properties.getProperty(key).split("\\|", 4);
            if (parts.length < 4) {
                continue;
            }
            try {
                int slot = Integer.parseInt(key);
                TAKEN.put(slot, new Cut(parts[0], parts[3], Float.parseFloat(parts[1]), parts[2]));
            } catch (NumberFormatException ignored) {
                // Une ligne abîmée libère son sillon plutôt que d'empêcher les autres de se charger.
            }
        }
        if (!TAKEN.isEmpty()) {
            Lanterne.LOG.info("[ATELIER] {} sillon(s) gravé(s) relu(s).", TAKEN.size());
        }
    }

    public static void inscribe() {
        Properties properties = new Properties();
        TAKEN.forEach((slot, cut) -> properties.setProperty(String.valueOf(slot),
                cut.hash() + "|" + cut.seconds() + "|" + cut.author() + "|" + cut.title()));
        try {
            Files.createDirectories(Groove.cacheFolder());
            try (OutputStream out = Files.newOutputStream(ledger())) {
                properties.store(out, "Lanterne - sillons graves. <sillon> = <empreinte>|<s>|<auteur>|<titre>."
                        + " Supprimer une ligne libere le sillon ; le disque correspondant devient muet.");
            }
        } catch (IOException problem) {
            Lanterne.LOG.warn("[ATELIER] registre des sillons non écrit : {}", problem.getMessage());
        }
    }
}
