package fr.clubcitrouille.lanterne.content.painting;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.imageio.ImageIO;

/**
 * Le moulin : il broie une image du disque en quelque chose que le jeu peut porter.
 *
 * <h2>Tout le travail a lieu ici, une seule fois</h2>
 *
 * <p>C'est la règle qui commande tout ce paquet : <b>rien de coûteux ne doit se produire pendant une
 * image</b>. Le ré-échantillonnage, la conversion de format, le calcul d'empreinte, l'encodage —
 * tout cela est fait <em>à l'import</em>, une fois, au démarrage du serveur ou sur commande. Ce qui
 * en sort est directement utilisable et ne sera plus jamais retouché.
 *
 * <p>Le mod dont ce paquet reprend l'idée — Immersive Paintings, sous GPL-3.0, dont Lanterne partage
 * la licence — fait l'inverse sur un point précis : il maintient plusieurs définitions d'une même
 * image et choisit laquelle utiliser <em>à chaque image rendue</em>, en calculant une densité de
 * pixels à partir de la distance et du champ de vision. C'est un niveau de détail fait à la main, en
 * Java, soixante fois par seconde et par tableau visible. Ici, ce travail est confié au matériel :
 * une seule image, et les mipmaps de la mosaïque font le reste (voir {@link Mosaic}).
 *
 * <h2>Le ré-échantillonnage : par moitiés successives, puis une passe bicubique</h2>
 *
 * <p>Réduire une image de 4000 pixels à 1024 en un seul appel bilinéaire donne un résultat crénelé :
 * le filtre ne regarde que quatre pixels source pour en produire un, et ignore donc quinze
 * seizièmes de l'information. Le remède classique est de réduire <b>par moitiés</b> tant qu'on est à
 * plus du double de la cible — chaque moitié étant une moyenne exacte de quatre pixels — puis de
 * finir par une passe bicubique sur le dernier facteur, qui vaut alors entre un et deux.
 *
 * <p>Le résultat est net et sans scintillement, et le coût total reste inférieur à celui d'un unique
 * filtre de qualité appliqué à la taille d'origine : la première moitié coûte le quart de l'image,
 * la deuxième le seizième, et la série converge.
 *
 * <h2>Ce qui est refusé, et comment</h2>
 *
 * <p>Un refus doit être <b>propre</b> : nommer le fichier, dire pourquoi, et continuer avec les
 * autres. Un import qui s'arrête au premier fichier douteux laisse le joueur devant un dossier qui
 * « ne marche pas » sans lui dire lequel. Les causes de refus sont donc des exceptions porteuses
 * d'un message lisible, et jamais des exceptions silencieuses.
 *
 * <p>Le poids du fichier est vérifié <b>avant</b> toute lecture. C'est important : une image
 * malformée de deux cents mébioctets ne doit pas pouvoir faire tomber un serveur par sa seule
 * présence dans un dossier. On ne décode que ce qu'on a déjà accepté.
 */
public final class Mill {
    /** Extensions reconnues. Le jeu n'affichera jamais autre chose qu'un PNG en sortie. */
    private static final String[] ACCEPTED = {".png", ".jpg", ".jpeg", ".bmp", ".gif"};

    private Mill() {}

    /** Le résultat d'un passage au moulin : les métadonnées, et les octets PNG. */
    public record Ground(Plate plate, byte[] png) {}

    /** Le fichier porte-t-il une extension que l'on sait lire ? */
    public static boolean readable(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        for (String extension : ACCEPTED) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Broie un fichier image et rend de quoi le servir.
     *
     * @param file le fichier source, tel que le joueur l'a déposé.
     * @param maxSide côté maximal autorisé après réduction, en pixels.
     * @param maxBytes poids maximal du fichier source, en octets.
     * @throws IOException si le fichier est refusé, illisible, ou vide. Le message est destiné à
     *     être lu par un humain dans le journal.
     */
    public static Ground press(Path file, int maxSide, long maxBytes) throws IOException {
        long weight = Files.size(file);
        if (weight > maxBytes) {
            throw new IOException("trop lourd : " + (weight / 1024L) + " Kio pour une limite de "
                    + (maxBytes / 1024L) + " Kio");
        }
        if (weight == 0L) {
            throw new IOException("fichier vide");
        }

        return grind(Files.readAllBytes(file), clip(stripExtension(file.getFileName().toString())),
                maxSide);
    }

    /**
     * Broie des octets déjà en mémoire — une image téléchargée, ou lue par le sélecteur du système.
     *
     * <h2>Pourquoi le même chemin que pour un fichier</h2>
     *
     * <p>Une image venue d'un lien et une image venue du dossier doivent produire la <b>même
     * empreinte</b> si ce sont les mêmes pixels. Sans cela, un joueur qui colle l'adresse d'une image
     * qu'un autre a déjà posée la ferait retransmettre à tout le serveur pour rien.
     *
     * <p>C'est pour cette raison que la lecture de fichier et la lecture d'octets convergent ici dès
     * la deuxième ligne : un seul décodage, un seul ré-échantillonnage, un seul encodage, une seule
     * empreinte. Deux chemins parallèles auraient fini par diverger d'un arrondi.
     *
     * @param raw les octets du fichier source, tels qu'ils sont arrivés.
     * @param name le nom à afficher.
     * @param maxSide côté maximal autorisé après réduction, en pixels.
     */
    public static Ground grind(byte[] raw, String name, int maxSide) throws IOException {
        if (raw.length == 0) {
            throw new IOException("fichier vide");
        }
        if (!looksLikeImage(raw)) {
            throw new IOException("ce n'est pas une image (aucune signature de format reconnue)");
        }
        BufferedImage source;
        try (InputStream in = new java.io.ByteArrayInputStream(raw)) {
            source = ImageIO.read(in);
        }
        if (source == null) {
            throw new IOException("format non reconnu par le décodeur d'images");
        }
        if (source.getWidth() < 1 || source.getHeight() < 1) {
            throw new IOException("dimensions nulles");
        }

        BufferedImage shrunk = shrink(source, maxSide);
        byte[] png = encode(shrunk);
        String hash = fingerprint(png);
        return new Ground(new Plate(hash, clip(name), shrunk.getWidth(), shrunk.getHeight()), png);
    }

    /**
     * Les octets commencent-ils par la signature d'un format d'image connu ?
     *
     * <h2>On ne croit ni l'extension, ni l'en-tête HTTP</h2>
     *
     * <p>Une adresse qui finit par {@code .png} peut rendre du HTML ; un serveur peut annoncer
     * {@code image/png} et envoyer autre chose. Les deux se contrôlent depuis l'autre bout du fil et
     * ne valent donc rien. Les premiers octets du fichier, eux, sont le fichier.
     *
     * <p>Ce test n'est pas une garantie de sûreté — le décodeur d'images en est une, et c'est lui qui
     * refusera un PNG malformé. C'est un <b>filtre bon marché</b> : il évite de lancer un décodeur
     * sur une page d'erreur de deux kilo-octets, et surtout il rend un message compréhensible
     * (« ce n'est pas une image ») là où le décodeur dirait seulement « null ».
     */
    public static boolean looksLikeImage(byte[] raw) {
        if (raw.length < 8) {
            return false;
        }
        int b0 = raw[0] & 0xFF;
        int b1 = raw[1] & 0xFF;
        int b2 = raw[2] & 0xFF;
        int b3 = raw[3] & 0xFF;
        // PNG : 89 50 4E 47
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
            return true;
        }
        // JPEG : FF D8 FF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
            return true;
        }
        // GIF : « GIF8 »
        if (b0 == 'G' && b1 == 'I' && b2 == 'F' && b3 == '8') {
            return true;
        }
        // BMP : « BM »
        return b0 == 'B' && b1 == 'M';
    }

    /**
     * Réduit une image pour que son plus grand côté ne dépasse pas {@code maxSide}.
     *
     * <p>Une image déjà assez petite est tout de même recopiée en {@code TYPE_INT_ARGB}. Ce n'est
     * pas du gaspillage : l'uniformité de format garantit que l'empreinte d'une même image ne change
     * pas selon qu'elle venait d'un JPEG en niveaux de gris ou d'un PNG indexé, et que les octets
     * reçus par le client se transposent directement dans la mosaïque sans conversion par pixel.
     */
    private static BufferedImage shrink(BufferedImage source, int maxSide) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        int targetW = width;
        int targetH = height;
        if (longest > maxSide) {
            double factor = (double) maxSide / (double) longest;
            targetW = Math.max(1, (int) Math.round(width * factor));
            targetH = Math.max(1, (int) Math.round(height * factor));
        }

        BufferedImage current = source;
        int currentW = width;
        int currentH = height;
        // Par moitiés tant qu'on est à plus du double de la cible — voir le Javadoc de classe.
        while (currentW / 2 >= targetW && currentH / 2 >= targetH && currentW > 1 && currentH > 1) {
            int nextW = Math.max(targetW, currentW / 2);
            int nextH = Math.max(targetH, currentH / 2);
            current = draw(current, nextW, nextH, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            currentW = nextW;
            currentH = nextH;
        }
        // La dernière passe, sur un facteur compris entre un et deux : le bicubique y est net sans
        // être crénelé, ce qui n'aurait pas été vrai sur le facteur d'origine.
        return draw(current, targetW, targetH, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage draw(BufferedImage source, int width, int height, Object hint) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /**
     * Encode en PNG.
     *
     * <h2>Pourquoi PNG et non JPEG, alors que le JPEG serait plus léger</h2>
     *
     * <p>Parce que le JPEG n'a pas de canal alpha, et qu'une image transparente — un logo, un motif
     * découpé — se retrouverait sur fond noir. Parce qu'il introduit des artefacts en damier que
     * l'agrandissement d'un tableau sur un mur rend parfaitement visibles. Et parce que la
     * différence de poids est payée <b>une fois</b>, à la première transmission, alors que le défaut
     * visuel serait payé à chaque regard.
     */
    private static byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("aucun encodeur PNG disponible dans cette machine virtuelle");
        }
        return out.toByteArray();
    }

    /** SHA-256 tronqué à seize octets, en hexadécimal. Voir {@link Plate#hash()}. */
    public static String fingerprint(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] full = digest.digest(data);
            byte[] cut = new byte[16];
            System.arraycopy(full, 0, cut, 0, 16);
            return HexFormat.of().formatHex(cut);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 est exigé de toute machine virtuelle Java depuis la version 1.4. Si elle
            // manque, ce n'est pas une panne à gérer, c'est un environnement cassé.
            throw new IllegalStateException("SHA-256 absent de cette machine virtuelle", impossible);
        }
    }

    /**
     * Les dimensions d'un PNG, lues dans son entête.
     *
     * <h2>Vingt-quatre octets au lieu d'un décodage complet</h2>
     *
     * <p>Un PNG commence toujours par une signature de huit octets, puis par un bloc
     * {@code IHDR} dont les deux premiers champs sont la largeur et la hauteur, en gros-boutien. Il
     * suffit donc de lire vingt-quatre octets pour connaître la taille d'une image, là où
     * {@code ImageIO.read} décompresserait le fichier entier.
     *
     * <p>Cela sert au démarrage : reprendre au catalogue une image déjà préparée lors d'un
     * lancement précédent ne demande que ses dimensions, et les redécoder toutes coûterait
     * exactement ce que le cache est censé éviter.
     *
     * @return {@code {largeur, hauteur}}, ou {@code null} si ce n'est pas un PNG lisible.
     */
    public static int[] measure(Path file) {
        byte[] head = new byte[24];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(head, 0, head.length);
            if (read < head.length) {
                return null;
            }
        } catch (IOException unreadable) {
            return null;
        }
        return measure(head);
    }

    /**
     * Les dimensions d'un PNG déjà en mémoire.
     *
     * <p>Le serveur s'en sert sur une image qu'un joueur vient d'apporter, <b>avant</b> de l'accepter
     * au catalogue. C'est le seul moyen de refuser une image de trente mille pixels de côté sans la
     * décoder : la décoder pour mesurer, c'est déjà allouer les trois gigaoctets qu'on voulait
     * refuser.
     *
     * @return {@code {largeur, hauteur}}, ou {@code null} si ce n'est pas un PNG lisible.
     */
    public static int[] measure(byte[] png) {
        if (png.length < 24) {
            return null;
        }
        // Signature PNG : 137 P N G \r \n 26 \n
        if ((png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
            return null;
        }
        int width = bigInt(png, 16);
        int height = bigInt(png, 20);
        return width > 0 && height > 0 ? new int[] {width, height} : null;
    }

    private static int bigInt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24)
                | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8)
                | (data[at + 3] & 0xFF);
    }

    /** L'empreinte est-elle bien formée ? Un client peut envoyer n'importe quoi. */
    public static boolean plausible(String hash) {
        if (hash == null || hash.length() != Plate.HASH_LENGTH) {
            return false;
        }
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static String clip(String raw) {
        String trimmed = raw.replace('_', ' ').trim();
        if (trimmed.isEmpty()) {
            return "sans titre";
        }
        return trimmed.length() > Plate.NAME_LIMIT
                ? trimmed.substring(0, Plate.NAME_LIMIT) : trimmed;
    }
}
