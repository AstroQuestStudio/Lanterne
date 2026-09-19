import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Outil ponctuel, pas partie du mod : fabrique les 16 variantes « texture connectee » (methode
 * CTM simplifiee a 16 tuiles — 4 bits, un par bord N/E/S/O) du verre et du verre teinte, par
 * RECADRAGE/MASQUAGE de la texture vanilla reelle — jamais de dessin artistique.
 *
 * <h2>Ce que la texture vanilla contient reellement (verifie par lecture de pixels bruts, pas
 * suppose)</h2>
 *
 * <p>{@code glass.png}, {@code tinted_glass.png} et les seize {@code X_stained_glass.png} du jar
 * client 26.3 partagent tous le meme dessin : un anneau d'UN pixel de bord, plus opaque (alpha
 * proche du plein), entourant un remplissage central plus transparent — plus quelques pixels de
 * « reflet » epars a l'interieur, qui ne touchent aucun bord. Verifie sur cinq textures
 * differentes (glass, tinted_glass, white/red/black_stained_glass) par un dump ARGB brut :
 * {@code FFD0EAE9} (bord) contre {@code 00808080} (interieur transparent) pour glass.png,
 * {@code A3xxxxxx} (bord) contre {@code 66xxxxxx} (interieur) pour chaque verre teinte. La
 * constante {@link #BORDER} en decoule ; {@link #detectBorder} la revalide a l'execution sur
 * CHAQUE texture source plutot que de faire confiance a une supposition figee.
 *
 * <h2>L'operation : copier l'interieur PAR-DESSUS le bord, jamais l'inverse</h2>
 *
 * <p>Pour chacun des 16 bitmasks (bit0=HAUT, bit1=DROITE, bit2=BAS, bit3=GAUCHE — un bit a 1 veut
 * dire « ce bord est connecte a un voisin du meme type, efface-le »), la ligne/colonne de bord
 * concernee est REMPLACEE par la ligne/colonne immediatement a l'interieur (rangee 1 pour le
 * bord HAUT, rangee h-2 pour BAS, etc.) — un copier-coller de pixels reels de la texture
 * d'origine, jamais une couleur inventee. Les quatre operations lisent TOUJOURS la texture
 * SOURCE (jamais le tampon de sortie deja modifie) : un coin ou deux bords se rencontrent (les
 * deux bits a 1) obtient donc un resultat independant de l'ordre dans lequel les bords sont
 * traites.
 *
 * <h2>Ce qui n'est PAS fait ici, et pourquoi</h2>
 *
 * <p>Ceci couvre le verre et le verre teinte PLEIN CUBE (glass, tinted_glass, les seize couleurs
 * de stained_glass) — pas les vitres ({@code glass_pane}). Une vitre vanilla est un modele
 * MULTIPART (poteau + jusqu'a quatre bras N/E/S/O, cinq fichiers de modele distincts par
 * couleur, tous echantillonnant la MEME texture {@code glass_pane_top.png} a des endroits
 * differents) — la connecter en texture demanderait de remapper les UV de chaque piece
 * separement, un chantier a part. Voir le Javadoc de {@code Jointure.java} (cote mod) pour le
 * detail de cette decision.
 *
 * <p>Usage : {@code java GenerateJointureTextures.java <jar-client-patche> <dossier-ressources-mod>}
 * Exemple : {@code java tools/GenerateJointureTextures.java
 * build/moddev/artifacts/minecraft-patched-26.3.0.3-beta.jar
 * src/main/resources/assets/lanterne/textures/block/jointure}
 *
 * <p>A relancer seulement si les textures vanilla de verre changent de dessin (nouvelle version
 * de jeu) — les 288 PNG produits (18 familles x 16 bitmasks) sont commit tels quels, pas
 * regeneres au demarrage du mod : un mod qui ecrit des fichiers de ressources a l'execution est
 * un risque inutile pour un resultat entierement deterministe a l'avance.
 */
public class GenerateJointureTextures {
    /** bit0=HAUT (rangee 0), bit1=DROITE (colonne max), bit2=BAS (rangee max), bit3=GAUCHE (colonne 0).
     * Meme convention, au bit pres, que {@code Jointure.EDGE_TOP/RIGHT/BOTTOM/LEFT} cote mod — une
     * divergence entre les deux ferait pointer chaque quad vers le mauvais bord efface. */
    static final int TOP = 1;
    static final int RIGHT = 2;
    static final int BOTTOM = 4;
    static final int LEFT = 8;

    /** Familles de texture couvertes — nom de fichier vanilla SANS extension, sous
     * {@code assets/minecraft/textures/block/}. Verre simple + verre teinte + les seize couleurs. */
    static final String[] FAMILIES = {
            "glass", "tinted_glass",
            "white_stained_glass", "orange_stained_glass", "magenta_stained_glass",
            "light_blue_stained_glass", "yellow_stained_glass", "lime_stained_glass",
            "pink_stained_glass", "gray_stained_glass", "light_gray_stained_glass",
            "cyan_stained_glass", "purple_stained_glass", "blue_stained_glass",
            "brown_stained_glass", "green_stained_glass", "red_stained_glass",
            "black_stained_glass",
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: GenerateJointureTextures <jar-client-patche> <dossier-sortie>");
            System.exit(1);
        }
        String jarPath = args[0];
        Path out = Paths.get(args[1]);
        Files.createDirectories(out);

        Map<String, BufferedImage> sources = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(jarPath)) {
            for (String family : FAMILIES) {
                String entryName = "assets/minecraft/textures/block/" + family + ".png";
                ZipEntry entry = zf.getEntry(entryName);
                if (entry == null) {
                    System.err.println("MANQUANT dans le jar : " + entryName);
                    continue;
                }
                try (InputStream is = zf.getInputStream(entry)) {
                    BufferedImage img = ImageIO.read(is);
                    if (img == null) {
                        System.err.println("Illisible : " + entryName);
                        continue;
                    }
                    sources.put(family, img);
                }
            }
        }
        System.out.println("Textures source lues : " + sources.size() + "/" + FAMILIES.length);

        int written = 0;
        for (Map.Entry<String, BufferedImage> e : sources.entrySet()) {
            String family = e.getKey();
            BufferedImage src = e.getValue();
            int border = detectBorder(src);
            if (border <= 0) {
                System.err.println("REFUS pour " + family
                        + " : aucun anneau de bord detecte (texture sans le motif attendu) — variantes non generees.");
                continue;
            }
            Path familyDir = out.resolve(family);
            Files.createDirectories(familyDir);
            for (int mask = 0; mask < 16; mask++) {
                BufferedImage variant = clearBorder(src, border, mask);
                ImageIO.write(variant, "png", familyDir.resolve(mask + ".png").toFile());
                written++;
            }
            System.out.println(family + " : bord=" + border + "px, 16 variantes ecrites.");
        }
        System.out.println("Total : " + written + " PNG ecrits sous " + out);
    }

    /**
     * Confirme, sur CETTE texture precise, qu'un anneau de bord d'exactement {@code depth} pixels
     * existe avant de s'en servir — jamais une supposition figee. Compare l'alpha moyen de
     * l'anneau exterieur a celui de la rangee immediatement interieure ; un ecart net (>= 24 sur
     * 255) a la profondeur 1 confirme le motif observe sur les cinq textures verifiees a la main
     * (voir le Javadoc de classe). Retourne 0 si aucun ecart net n'est trouve — l'appelant doit
     * alors refuser de generer des variantes pour cette texture plutot que de deviner.
     */
    static int detectBorder(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        if (w < 4 || h < 4) {
            return 0;
        }
        double outerAlpha = ringAlpha(img, 0);
        double innerAlpha = ringAlpha(img, 1);
        if (Math.abs(outerAlpha - innerAlpha) >= 24.0) {
            return 1;
        }
        return 0;
    }

    /** Alpha moyen des pixels du perimetre carre a la profondeur donnee (0 = bord exterieur). */
    static double ringAlpha(BufferedImage img, int depth) {
        int w = img.getWidth(), h = img.getHeight();
        long sum = 0;
        int n = 0;
        for (int x = depth; x < w - depth; x++) {
            sum += alphaOf(img, x, depth);
            sum += alphaOf(img, x, h - 1 - depth);
            n += 2;
        }
        for (int y = depth + 1; y < h - 1 - depth; y++) {
            sum += alphaOf(img, depth, y);
            sum += alphaOf(img, w - 1 - depth, y);
            n += 2;
        }
        return n == 0 ? 0 : sum / (double) n;
    }

    static int alphaOf(BufferedImage img, int x, int y) {
        return (img.getRGB(x, y) >>> 24) & 0xFF;
    }

    /**
     * Produit une copie de {@code src} ou chaque pixel du cadre de bord est efface (remplace par
     * un echantillon interieur, decale vers l'interieur par reflet) DES QU'IL APPARTIENT A UN
     * BORD MARQUE CONNECTE par {@code mask} — meme un pixel de coin, qui appartient a DEUX bords
     * a la fois.
     *
     * <h2>Pourquoi un coin ne peut pas se traiter comme deux bords independants</h2>
     *
     * <p>Une premiere version traitait chaque bord par une copie de rangee/colonne entiere,
     * appliquee independamment. Elle echouait sur les coins : le cadre vanilla est plein sur SES
     * QUATRE cotes (chaque rangee ET chaque colonne a ses deux extremites bordees), donc la
     * "rangee interieure" utilisee pour effacer le HAUT contenait encore, a ses deux extremites,
     * des pixels de bord GAUCHE/DROITE — recopier cette rangee entiere laissait le coin visible.
     *
     * <p>Ici, chaque pixel du cadre decide seul, selon les bords auxquels IL appartient (un coin
     * appartient a deux bords, un pixel de bord droit n'appartient qu'a un seul) : s'il
     * appartient a AU MOINS UN bord marque connecte, il est efface — jamais besoin que les DEUX
     * bords d'un coin soient connectes a la fois. C'est exactement le rendu attendu : si seul le
     * HAUT se connecte, toute la rangee du haut (coins compris) devient continue avec le voisin
     * du dessus, tandis que la colonne de GAUCHE reste bordee sur le reste de sa hauteur — un
     * bord en "L", pas un artefact.
     *
     * @param border profondeur du cadre en pixels (voir {@link #detectBorder}).
     * @param mask   bords a effacer, voir {@link #TOP}/{@link #RIGHT}/{@link #BOTTOM}/{@link #LEFT}.
     */
    static BufferedImage clearBorder(BufferedImage src, int border, int mask) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        boolean top = (mask & TOP) != 0;
        boolean bottom = (mask & BOTTOM) != 0;
        boolean left = (mask & LEFT) != 0;
        boolean right = (mask & RIGHT) != 0;
        for (int y = 0; y < h; y++) {
            boolean belongsTop = y < border;
            boolean belongsBottom = y >= h - border;
            for (int x = 0; x < w; x++) {
                boolean belongsLeft = x < border;
                boolean belongsRight = x >= w - border;
                boolean onFrame = belongsTop || belongsBottom || belongsLeft || belongsRight;
                boolean cleared = onFrame
                        && ((belongsTop && top) || (belongsBottom && bottom)
                                || (belongsLeft && left) || (belongsRight && right));
                if (!cleared) {
                    out.setRGB(x, y, src.getRGB(x, y));
                    continue;
                }
                int srcX = belongsLeft ? x + border : (belongsRight ? x - border : x);
                int srcY = belongsTop ? y + border : (belongsBottom ? y - border : y);
                out.setRGB(x, y, src.getRGB(srcX, srcY));
            }
        }
        return out;
    }
}
