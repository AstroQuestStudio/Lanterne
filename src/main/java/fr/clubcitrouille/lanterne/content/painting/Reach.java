package fr.clubcitrouille.lanterne.content.painting;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Aller chercher : une image au bout d'un lien, ou au fond du disque du joueur.
 *
 * <h2>Pourquoi tout se passe chez le client, et pourquoi ce n'est pas négociable</h2>
 *
 * <p>Il aurait été plus court d'envoyer l'adresse au serveur et de le laisser télécharger : un seul
 * téléchargement pour tout le monde, pas de protocole de remontée. C'est exactement la faille dite
 * <b>SSRF</b>, et elle est grave.
 *
 * <p>Un serveur Minecraft tourne presque toujours dans un réseau où d'autres choses écoutent : une
 * base de données sur le port 3306, un panneau d'administration sur 8080, l'interface de métadonnées
 * d'un hébergeur sur une adresse locale bien connue. Un joueur qui colle
 * {@code http://127.0.0.1:8080/...} et se fait rendre le résultat sous forme d'image n'attaque pas
 * le jeu : il se sert du serveur comme d'une sonde dans un réseau auquel il n'a pas accès. Aucune
 * liste d'adresses interdites ne referme complètement cette porte — les redirections, les noms qui
 * résolvent vers des adresses privées et l'IPv6 laissent toujours un passage.
 *
 * <p>La porte se referme en <b>ne la construisant pas</b>. Le client télécharge, avec son propre
 * réseau, sous sa propre responsabilité, et le serveur ne reçoit jamais qu'une image déjà réduite
 * qu'il borne comme si elle venait d'un dossier.
 *
 * <h2>Ce qui est borné, et où</h2>
 *
 * <ul>
 *   <li><b>La taille</b>, comptée pendant la lecture et non après : un serveur hostile peut annoncer
 *       trois kilo-octets et en envoyer trois gigaoctets. On coupe dès le dépassement, sans avoir
 *       jamais tout gardé.</li>
 *   <li><b>Le délai</b>, à la connexion et à la réponse.</li>
 *   <li><b>Les redirections</b> : la machine virtuelle en autorise cinq au maximum et refuse de
 *       passer de HTTPS à HTTP, ce qui est exactement ce qu'on veut.</li>
 *   <li><b>Le format</b>, jugé sur les premiers octets du contenu et jamais sur l'extension de
 *       l'adresse ni sur l'en-tête annoncé par le serveur : ces deux-là se décident depuis l'autre
 *       bout du fil et ne valent donc rien.</li>
 * </ul>
 *
 * <h2>Rien de tout cela sur le fil de rendu</h2>
 *
 * <p>Un téléchargement dure des secondes et le sélecteur de fichiers du système <b>bloque jusqu'à ce
 * que l'utilisateur clique</b> — potentiellement une minute. Les deux tournent donc sur un fil à
 * eux, et le résultat revient à l'écran par {@code Minecraft.execute}. L'écran reste peint, la
 * touche d'échappement répond, et l'opération s'annule.
 */
/*
 * Cette classe ne vit que du cote client.
 *
 * Elle ne porte PLUS « @OnlyIn(Dist.CLIENT) », et ce n'est pas un oubli. Depuis la
 * 26.1, cette annotation ne retire plus rien a l'execution : NeoForge le signale
 * lui-meme, et au niveau ERREUR, sept fois de suite au demarrage de chaque serveur
 * dedie. Sept fausses erreurs dans un journal, c'est sept lignes qui masquent la
 * vraie le jour ou elle arrive.
 *
 * Ce qui garde reellement cette classe hors du serveur n'a jamais ete l'annotation :
 * c'est qu'aucun chemin commun ne la nomme. Le code commun ne connait que des
 * interfaces, implementees du seul cote client — lecon apprise a la dure, par un
 * plantage de serveur dedie sur « ClassNotFoundException: Screen », parce qu'un
 * corps de lambda est compile dans une methode de sa classe englobante et charge
 * avec elle.
 *
 * L'annotation documentait donc une garantie qu'elle ne tenait pas. Le commentaire,
 * lui, ne pretend rien tenir.
 */
public final class Reach {
    /**
     * Le fil qui va chercher.
     *
     * <p>Un seul, en démon : deux téléchargements simultanés n'ont aucun sens puisqu'un seul écran
     * est ouvert, et un fil non-démon retiendrait le jeu à la fermeture pendant qu'un serveur lent
     * finit de répondre.
     */
    private static final ExecutorService ERRANDS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Lanterne-courses");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Le client HTTP, construit une fois.
     *
     * <p>{@code Redirect.NORMAL} et non {@code ALWAYS} : la différence est que {@code NORMAL} refuse
     * de suivre une redirection qui rétrograde de HTTPS vers HTTP. Un lien sûr ne doit pas pouvoir
     * devenir un lien en clair en cours de route.
     */
    private static HttpClient client;

    private Reach() {}

    /** Ce qu'on rapporte : des octets bruts, et un nom présentable. */
    public record Haul(byte[] raw, String name) {}

    /**
     * Ce qu'on est venu chercher.
     *
     * <h2>Pourquoi un paramètre et non deux classes</h2>
     *
     * <p>Aller chercher une image et aller chercher un morceau, c'est le même travail : les mêmes
     * bornes, le même délai, le même fil, le même sélecteur de fichiers. Seuls diffèrent les motifs
     * proposés au système et le test de signature. Deux classes auraient recopié cent lignes pour
     * changer deux tableaux de chaînes.
     *
     * @param title le titre de la fenêtre du système.
     * @param patterns les motifs d'extension, pour le sélecteur seulement — ils orientent, ils ne
     *     décident pas.
     * @param described comment le système nomme le filtre.
     */
    public record Kind(String title, String[] patterns, String described,
            java.util.function.Predicate<byte[]> accepts) {}

    /** Les images : ce que le décodeur du jeu sait lire. */
    public static final Kind IMAGES = new Kind(
            "Choisis une image pour ton tableau",
            new String[] {"*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"},
            "Images (png, jpg, bmp, gif)",
            Mill::looksLikeImage);

    /**
     * Les morceaux.
     *
     * <p>Le test d'acceptation est <b>volontairement large</b> — on refuse seulement ce qui est
     * manifestement du texte. La vraie vérification se fait plus tard, dans {@code content.disc.Press},
     * qui reconnaît le format sur la signature et sait dire précisément ce qu'il refuse. Trancher ici
     * aurait voulu dire écrire deux fois la même reconnaissance, et se tromper à l'un des deux
     * endroits.
     */
    public static final Kind AUDIO = new Kind(
            "Choisis un morceau à graver",
            new String[] {"*.ogg", "*.mp3", "*.wav", "*.flac", "*.m4a", "*.aac", "*.opus", "*.wma"},
            "Audio (ogg, mp3, wav, flac...)",
            raw -> raw.length > 16 && raw[0] != '<' && raw[0] != '{');

    /** Ce qui s'est passé, pour qui attend. */
    public interface Ear {
        /** Une étape franchie — texte court, destiné à être affiché tel quel. */
        void progress(String note);

        /** Fini. */
        void done(Haul haul);

        /** Raté. Le message est écrit pour un humain, pas pour un journal. */
        void failed(String reason);
    }

    // --- Le lien -----------------------------------------------------------

    /**
     * Télécharge une image depuis une adresse.
     *
     * <p>Toutes les réponses passent par {@code Ear}, y compris les refus, y compris ceux qui sont
     * décidés avant même la première connexion. L'appelant n'a donc qu'un seul chemin à traiter.
     */
    public static void pull(String address, PaintingRoll.Rules rules, Kind kind, Ear ear) {
        ERRANDS.submit(() -> {
            try {
                URI uri = parse(address, rules);
                ear.progress("Connexion à " + uri.getHost() + "…");
                byte[] raw = fetch(uri, ceiling(rules, kind), kind, ear);
                ear.done(new Haul(raw, nameOf(uri)));
            } catch (IllegalArgumentException refusal) {
                ear.failed(refusal.getMessage());
            } catch (IOException problem) {
                ear.failed("Hôte injoignable : " + short_(problem));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                ear.failed("Téléchargement interrompu.");
            } catch (RuntimeException unexpected) {
                Lanterne.LOG.warn("[ATELIER] téléchargement raté", unexpected);
                ear.failed("Échec : " + short_(unexpected));
            }
        });
    }

    /**
     * Vérifie l'adresse avant d'ouvrir quoi que ce soit.
     *
     * <p>Les refus sont donnés dans l'ordre où ils coûtent le moins cher, et chacun dit ce qu'il
     * faut faire plutôt que ce qui ne va pas.
     */
    private static URI parse(String address, PaintingRoll.Rules rules) {
        if (!rules.links()) {
            throw new IllegalArgumentException("Ce serveur n'accepte pas les liens."
                    + " Passe par « Depuis mon PC ».");
        }
        String trimmed = address == null ? "" : address.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Colle une adresse dans le champ.");
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Adresse illisible.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Seuls http et https sont acceptés.");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("Adresse sans hôte.");
        }
        if (!rules.allows(uri.getHost())) {
            throw new IllegalArgumentException("Ce serveur n'accepte que : "
                    + String.join(", ", rules.domains()));
        }
        return uri;
    }

    /**
     * Lit la réponse en comptant les octets au fur et à mesure.
     *
     * <h2>Pourquoi ne pas demander le corps en tableau d'octets</h2>
     *
     * <p>{@code BodyHandlers.ofByteArray()} tiendrait en une ligne et lirait <b>tout</b> avant de
     * rendre la main. Un serveur qui envoie un flux sans fin remplirait alors la mémoire du joueur
     * jusqu'à la panne, et il n'y aurait aucun endroit où l'arrêter.
     *
     * <p>En lisant le flux nous-mêmes, la coupure tombe au premier octet de trop. C'est quelques
     * lignes de plus et c'est la seule version qui tienne devant un hôte qui ne coopère pas.
     */
    private static byte[] fetch(URI uri, long ceiling, Kind kind, Ear ear)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Studio.linkTimeout()))
                .header("User-Agent", "Lanterne/" + Lanterne.ID)
                .header("Accept", "*/*")
                .GET()
                .build();
        HttpResponse<InputStream> response =
                http().send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalArgumentException("Le serveur a répondu " + response.statusCode() + ".");
        }
        // L'en-tête annoncé ne sert qu'à faire un message poli quand c'est visiblement une page web.
        // Il n'autorise rien : c'est le contenu qui décide, plus bas.
        String announced = response.headers().firstValue("content-type").orElse("");
        if (announced.startsWith("text/html")) {
            throw new IllegalArgumentException("Ce lien rend une page web, pas une image."
                    + " Ouvre l'image seule et copie SON adresse.");
        }

        java.io.ByteArrayOutputStream basket = new java.io.ByteArrayOutputStream(1 << 16);
        byte[] buffer = new byte[1 << 14];
        long total = 0L;
        long expected = response.headers().firstValueAsLong("content-length").orElse(-1L);
        try (InputStream in = response.body()) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > ceiling) {
                    throw new IllegalArgumentException("Image trop lourde : la limite est "
                            + (ceiling / 1024L) + " Kio.");
                }
                basket.write(buffer, 0, read);
                if (expected > 0L) {
                    ear.progress("Téléchargement " + (total * 100L / expected) + " %");
                } else {
                    ear.progress("Téléchargement " + (total / 1024L) + " Kio");
                }
            }
        }
        byte[] raw = basket.toByteArray();
        if (!kind.accepts().test(raw)) {
            throw new IllegalArgumentException("Le contenu reçu n'est pas du bon type.");
        }
        return raw;
    }

    /**
     * Le plafond d'octets, qui n'est pas le même pour une image et pour un morceau.
     *
     * <p>Une image réduite pèse un mébioctet, un morceau de musique en pèse vingt. Appliquer la
     * limite des images aux morceaux les aurait tous refusés ; appliquer celle des morceaux aux
     * images aurait laissé passer des fichiers absurdes. Chaque limite vit dans son propre réglage,
     * et c'est ici qu'on choisit laquelle regarder.
     */
    private static long ceiling(PaintingRoll.Rules rules, Kind kind) {
        return kind == AUDIO ? Studio.discMaxBytes() : (long) rules.maxSourceKib() * 1024L;
    }

    private static HttpClient http() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Studio.linkTimeout()))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return client;
    }

    /** Le dernier morceau du chemin, sans requête ni extension — de quoi nommer le tableau. */
    private static String nameOf(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        int slash = path.lastIndexOf('/');
        String tail = slash < 0 ? path : path.substring(slash + 1);
        if (tail.isBlank()) {
            return uri.getHost();
        }
        int dot = tail.lastIndexOf('.');
        return dot > 0 ? tail.substring(0, dot) : tail;
    }

    private static String short_(Throwable trouble) {
        String message = trouble.getMessage();
        if (message == null || message.isBlank()) {
            return trouble.getClass().getSimpleName();
        }
        return message.length() > 90 ? message.substring(0, 90) + "…" : message;
    }

    // --- Le disque du joueur -----------------------------------------------

    /**
     * Ouvre le sélecteur de fichiers du système.
     *
     * <h2>Pourquoi le sélecteur natif et pas un explorateur dessiné dans le jeu</h2>
     *
     * <p>Un explorateur maison, c'est une liste à faire défiler, une barre de chemin, des icônes, la
     * gestion des lecteurs sous Windows et des points de montage ailleurs, les raccourcis, les
     * dossiers sans droit de lecture. C'est beaucoup de code pour rendre un service que le système
     * d'exploitation rend déjà mieux — avec ses favoris, sa recherche et son aperçu.
     *
     * <p>{@code tinyfd} est livré avec LWJGL, donc déjà présent dans toute installation de
     * Minecraft : il n'y a aucune dépendance à ajouter.
     *
     * <h2>Deux pièges, et comment ils sont tenus</h2>
     *
     * <p><b>L'appel bloque</b> jusqu'à ce que l'utilisateur choisisse ou annule. Sur le fil de rendu,
     * le jeu se figerait complètement — fenêtre blanche, « ne répond pas ». D'où le fil à part.
     *
     * <p><b>Les filtres vivent dans la pile native.</b> {@code MemoryStack} est propre au fil qui
     * l'empile ; le bloc {@code try} garantit le dépilement même en cas d'exception. Oublier ce
     * dépilement corromprait la pile de ce fil pour tout le reste de la partie.
     *
     * <p>Reste une réserve honnête : sous macOS, ce sélecteur passe par {@code osascript} et
     * certaines versions du système exigent que les dialogues natifs soient ouverts depuis le fil
     * principal. Le comportement n'a pas pu être vérifié faute de machine ; en cas d'échec, les deux
     * autres voies — le lien et le dossier — restent entières.
     */
    public static void browse(PaintingRoll.Rules rules, Kind kind, Ear ear) {
        ERRANDS.submit(() -> {
            String picked;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(kind.patterns().length);
                for (String pattern : kind.patterns()) {
                    filters.put(stack.UTF8(pattern));
                }
                filters.flip();
                picked = TinyFileDialogs.tinyfd_openFileDialog(
                        kind.title(), "", filters, kind.described(), false);
            } catch (Throwable missing) {
                // Un système sans sélecteur natif — certaines distributions minimales — lève ici.
                // Ce n'est pas une panne du mod : on le dit, et les deux autres voies restent.
                Lanterne.LOG.warn("[ATELIER] sélecteur de fichiers indisponible", missing);
                ear.failed("Le sélecteur du système n'a pas pu s'ouvrir. Utilise un lien, ou dépose"
                        + " le fichier dans config/lanterne/tableaux/.");
                return;
            }
            if (picked == null || picked.isBlank()) {
                // Annulation : ce n'est pas une erreur, et l'écran ne doit pas afficher de rouge.
                ear.progress("");
                return;
            }
            try {
                Path file = Path.of(picked);
                long weight = Files.size(file);
                long ceiling = ceiling(rules, kind);
                if (weight > ceiling) {
                    ear.failed("Trop lourd : " + (weight / 1024L) + " Kio pour une limite de "
                            + (ceiling / 1024L) + " Kio.");
                    return;
                }
                ear.progress("Lecture de " + file.getFileName() + "…");
                byte[] raw = Files.readAllBytes(file);
                if (!kind.accepts().test(raw)) {
                    ear.failed("Ce fichier n'est pas d'un type reconnu.");
                    return;
                }
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                ear.done(new Haul(raw, dot > 0 ? name.substring(0, dot) : name));
            } catch (IOException problem) {
                ear.failed("Fichier illisible : " + short_(problem));
            }
        });
    }
}
