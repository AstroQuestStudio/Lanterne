import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Les images de l'Atelier : les deux objets, et les deux planches d'explication.
 *
 * <h2>Pourquoi un programme plutôt qu'un dessin</h2>
 *
 * <p>Une texture de seize pixels dessinée à la main dans un éditeur est un fichier binaire que
 * personne ne peut relire, corriger ni expliquer. Écrite en code, elle se lit comme le reste du
 * projet : on voit pourquoi ce pixel est là, on change une couleur en un endroit, et la regénération
 * est reproductible.
 *
 * <p>C'est la même raison qui a fait écrire {@code ItemTex} pour le carnet et la boussole, et
 * {@code Iso} pour les blocs. Ce fichier suit leur grammaire : seize sur seize, contour sombre,
 * palette restreinte, ambre du mod pour l'accent.
 *
 * <h2>Lancer</h2>
 *
 * <pre>
 * java tools/AtelierTex.java src/main/resources/assets/lanterne/textures/item docs/images
 * </pre>
 *
 * <p>Le premier dossier reçoit les textures de jeu, le second les images du README — les mêmes,
 * agrandies douze fois au plus proche voisin, parce qu'un lissage rendrait floue une image dont
 * toute la lisibilité tient à ses arêtes nettes.
 */
public final class AtelierTex {
    /** Le contour, commun à tous les objets du mod. */
    private static final int EDGE = 0xFF16161A;

    /** L'ambre du mod, du plus sombre au plus clair. */
    private static final int[] AMBER = {0xFFB4560E, 0xFFDE7A1C, 0xFFFFC857, 0xFFFFE9A8};

    /** Le bois du manche. */
    private static final int[] WOOD = {0xFF5A3A22, 0xFF7A5230, 0xFF9A6C42};

    /** L'acier de la virole et du bord du disque. */
    private static final int[] STEEL = {0xFF2A2A32, 0xFF41414C, 0xFF6B6B77, 0xFF8D8D99};

    /** Le vinyle : presque noir, mais pas noir — un noir pur écraserait le contour. */
    private static final int[] VINYL = {0xFF14141A, 0xFF1E1E26, 0xFF2C2C36};

    /** Les couleurs de la soie du pinceau, celles qu'un tableau y laisse. */
    private static final int[] PAINT = {0xFF3C6E9E, 0xFFB4452F, 0xFF4E8A4A};

    public static void main(String[] args) throws Exception {
        File textures = new File(args.length > 0 ? args[0] : ".");
        File docs = new File(args.length > 1 ? args[1] : ".");
        textures.mkdirs();
        docs.mkdirs();

        BufferedImage brush = brush();
        BufferedImage disc = disc();
        BufferedImage blank = blank_();
        ImageIO.write(brush, "png", new File(textures, "pinceau.png"));
        ImageIO.write(disc, "png", new File(textures, "disque.png"));
        ImageIO.write(blank, "png", new File(textures, "disque_vierge.png"));
        ImageIO.write(blow(brush, 12), "png", new File(docs, "pinceau.png"));
        ImageIO.write(blow(disc, 12), "png", new File(docs, "disque.png"));
        ImageIO.write(blow(blank, 12), "png", new File(docs, "disque_vierge.png"));

        // Le graveur : trois faces, comme tous les blocs de ce mod.
        File blocks = new File(textures.getParentFile(), "block");
        blocks.mkdirs();
        BufferedImage top = burnerTop(false);
        BufferedImage hot = burnerTop(true);
        BufferedImage side = burnerSide();
        BufferedImage bottom = burnerBottom();
        ImageIO.write(top, "png", new File(blocks, "graveur_top.png"));
        ImageIO.write(hot, "png", new File(blocks, "graveur_top_actif.png"));
        ImageIO.write(side, "png", new File(blocks, "graveur_side.png"));
        ImageIO.write(bottom, "png", new File(blocks, "graveur_bottom.png"));
        ImageIO.write(blow(top, 12), "png", new File(docs, "graveur_top.png"));
        ImageIO.write(blow(side, 12), "png", new File(docs, "graveur_side.png"));

        ImageIO.write(mosaic(), "png", new File(docs, "mosaique.png"));
        ImageIO.write(gallery(), "png", new File(docs, "tableau.png"));
        System.out.println("Écrit : pinceau, disque, disque_vierge, graveur, mosaique, tableau.");
    }

    /**
     * Le disque vierge : le même rond, sans étiquette et sans sillons.
     *
     * <h2>Ce qu'il faut qu'on lise d'un coup d'œil</h2>
     *
     * <p>Un disque vierge et un disque gravé se côtoient dans un inventaire, et les confondre fait
     * perdre du temps à chaque fois. Deux différences franches plutôt qu'une nuance : l'étiquette
     * d'ambre disparaît entièrement, et la surface devient <b>mate et claire</b> au lieu de noire et
     * brillante. Même à la taille d'une case, on ne se trompe pas.
     */
    private static BufferedImage blank_() {
        BufferedImage img = blank();
        double cx = 7.5d;
        double cy = 7.5d;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double dx = x - cx;
                double dy = y - cy;
                double r = Math.sqrt(dx * dx + dy * dy);
                if (r > 7.3d) {
                    continue;
                }
                if (r > 6.2d) {
                    set(img, x, y, EDGE);
                } else if (r < 1.05d) {
                    set(img, x, y, EDGE);
                } else {
                    // Une cire pâle, à peine dégradée : rien n'accroche l'œil, et c'est le but.
                    double light = Math.clamp((cy - dy * 1.2d) / 15.0d, 0d, 1d);
                    set(img, x, y, blend(0xFF6E6A63, 0xFFA7A29A, light));
                }
            }
        }
        set(img, 4, 3, 0xFFC4BFB6);
        return img;
    }

    /**
     * Le dessus du graveur : le plateau et son bras de lecture.
     *
     * @param hot vrai pour la variante en marche, où le sillon rougeoie.
     */
    private static BufferedImage burnerTop(boolean hot) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        // Le châssis de cuivre.
        fill(img, 0, 0, 16, 16, 0xFF8A5A3C);
        for (int i = 0; i < 16; i++) {
            set(img, i, 0, 0xFF6B4530);
            set(img, 0, i, 0xFF6B4530);
            set(img, i, 15, 0xFF5A3826);
            set(img, 15, i, 0xFF5A3826);
        }
        // Le plateau, rond et sombre.
        double cx = 7.0d;
        double cy = 8.0d;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double dx = x - cx;
                double dy = y - cy;
                double r = Math.sqrt(dx * dx + dy * dy);
                if (r > 5.6d) {
                    continue;
                }
                if (r > 5.0d) {
                    set(img, x, y, EDGE);
                } else if (r < 1.0d) {
                    set(img, x, y, hot ? AMBER[2] : STEEL[1]);
                } else {
                    boolean groove = Math.abs(r - 3.2d) < 0.45d;
                    int base = blend(VINYL[0], VINYL[1], Math.clamp((cy - dy) / 14.0d, 0d, 1d));
                    set(img, x, y, groove ? (hot ? AMBER[0] : VINYL[2]) : base);
                }
            }
        }
        // Le bras, en diagonale depuis le coin bas-droit.
        int[][] arm = {{13, 12}, {12, 11}, {11, 10}, {10, 9}, {9, 8}};
        for (int[] cell : arm) {
            set(img, cell[0], cell[1], STEEL[3]);
            set(img, cell[0], cell[1] + 1, STEEL[1]);
        }
        // La pointe, qui rougit quand elle grave : c'est le seul pixel qui dit « ça travaille ».
        set(img, 8, 7, hot ? AMBER[2] : STEEL[2]);
        return img;
    }

    /** Les faces : du cuivre rivé, avec une fente d'aération. */
    private static BufferedImage burnerSide() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        fill(img, 0, 0, 16, 16, 0xFF7A4E34);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                // Un grain discret, tiré d'un mélange déterministe : deux lancements du programme
                // donnent le même bloc, ce qu'un tirage aléatoire n'aurait pas garanti.
                int mixed = (x * 73 + y * 151) ^ (x * y * 31);
                if (Math.floorMod(mixed, 7) == 0) {
                    set(img, x, y, 0xFF6B4530);
                }
            }
        }
        for (int i = 0; i < 16; i++) {
            set(img, i, 0, 0xFF9A6647);
            set(img, i, 15, 0xFF4E3021);
        }
        // Les rivets aux quatre coins.
        for (int[] cell : new int[][] {{2, 2}, {13, 2}, {2, 13}, {13, 13}}) {
            set(img, cell[0], cell[1], STEEL[3]);
        }
        // La fente d'aération, au centre.
        for (int x = 4; x <= 11; x++) {
            set(img, x, 7, EDGE);
            set(img, x, 9, EDGE);
        }
        fill(img, 4, 8, 8, 1, 0xFF2A2018);
        return img;
    }

    /** Le dessous : de la pierre, comme tout socle de machine. */
    private static BufferedImage burnerBottom() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        fill(img, 0, 0, 16, 16, 0xFF57575E);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if ((x * 31 + y * 17) % 5 == 0) {
                    set(img, x, y, 0xFF4A4A51);
                }
            }
        }
        return img;
    }

    // --- Les deux objets ---------------------------------------------------

    /**
     * Le pinceau : manche de bois, virole d'acier, soie chargée de couleur.
     *
     * <h2>Trois couleurs sur la soie, et pourquoi</h2>
     *
     * <p>Une soie d'une seule teinte ferait un objet propre et muet : rien ne dirait qu'il sert à
     * poser des <em>images</em>. Trois taches — un bleu, un rouge, un vert — le disent d'un coup
     * d'œil, à la taille d'une case d'inventaire, sans texte et sans infobulle. C'est le seul endroit
     * de cette texture où la lisibilité prime sur le réalisme, et c'est assumé.
     */
    private static BufferedImage brush() {
        BufferedImage img = blank();

        // Le manche, en biais : un manche droit se confondrait avec une baguette ou une tige.
        // La diagonale est décrite ligne par ligne plutôt que par une équation, parce qu'à seize
        // pixels une équation donne des marches irrégulières qu'il faut de toute façon corriger.
        int[][] handle = {
            {10, 13}, {9, 12}, {9, 11}, {8, 10}, {8, 9},
        };
        for (int[] cell : handle) {
            int x = cell[0];
            int y = cell[1];
            fill(img, x, y, 2, 2, WOOD[1]);
            set(img, x, y, WOOD[2]);
        }
        // Le bout du manche, arrondi d'un pixel.
        set(img, 11, 14, WOOD[0]);
        set(img, 12, 14, EDGE);
        set(img, 11, 15, EDGE);

        // La virole d'acier, entre le bois et la soie : c'est elle qui fait lire « pinceau »
        // plutôt que « bâton taché ».
        fill(img, 6, 7, 3, 3, STEEL[2]);
        set(img, 6, 9, STEEL[1]);
        set(img, 8, 7, STEEL[3]);

        // La soie, évasée vers le haut à gauche.
        fill(img, 4, 3, 4, 4, AMBER[1]);
        set(img, 4, 3, AMBER[2]);
        set(img, 5, 3, AMBER[2]);
        set(img, 4, 4, AMBER[2]);
        set(img, 7, 6, AMBER[0]);
        set(img, 6, 6, AMBER[0]);
        // Les trois taches de couleur.
        set(img, 3, 4, PAINT[0]);
        set(img, 3, 5, PAINT[0]);
        set(img, 5, 2, PAINT[1]);
        set(img, 6, 3, PAINT[1]);
        set(img, 4, 6, PAINT[2]);
        set(img, 5, 6, PAINT[2]);

        outline(img);
        return img;
    }

    /**
     * Le disque : un vinyle noir, une étiquette d'ambre, un trou au centre.
     *
     * <h2>Les sillons ne sont pas décoratifs</h2>
     *
     * <p>Deux anneaux à peine plus clairs suffisent à faire lire « disque » plutôt que « jeton ». Ils
     * sont tracés à un pixel de largeur et à deux rayons seulement : à seize pixels, un troisième
     * anneau ne se distinguerait plus des deux autres et rendrait l'objet gris.
     *
     * <p>L'étiquette est ambre parce que c'est la couleur du mod, et qu'un disque de Minecraft se
     * reconnaît d'abord à la couleur de son étiquette — c'est la seule chose qui sépare visuellement
     * les treize disques de vanilla les uns des autres.
     */
    private static BufferedImage disc() {
        BufferedImage img = blank();
        double cx = 7.5d;
        double cy = 7.5d;

        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double dx = x - cx;
                double dy = y - cy;
                double r = Math.sqrt(dx * dx + dy * dy);
                if (r > 7.3d) {
                    continue;
                }
                if (r > 6.2d) {
                    // Le contour, sur un peu plus d'un pixel : à seize pixels, un contour d'un seul
                    // pixel se brise aux quarante-cinq degrés et le rond devient un octogone.
                    set(img, x, y, EDGE);
                    continue;
                }
                if (r < 3.3d) {
                    set(img, x, y, label(r, dy));
                    continue;
                }
                // Le vinyle, éclairé par le haut. Le dégradé est continu — un partage net en deux
                // moitiés, essayé d'abord, dessinait une ligne d'horizon au milieu du disque.
                double light = Math.clamp((cy - dy * 1.4d) / 15.0d, 0d, 1d);
                int base = blend(VINYL[0], VINYL[1], light);
                // Deux sillons, et deux seulement : un troisième ne se distinguerait plus des
                // autres et le disque virerait au gris uniforme.
                boolean groove = ring(r, 4.35d) || ring(r, 5.45d);
                set(img, x, y, groove ? blend(base, VINYL[2], 0.85d) : base);
            }
        }
        // Un éclat en haut à gauche : deux pixels suffisent à faire lire une surface brillante.
        set(img, 4, 2, blend(VINYL[2], STEEL[2], 0.5d));
        set(img, 3, 3, blend(VINYL[2], STEEL[2], 0.25d));
        return img;
    }

    /** Un pixel est-il sur l'anneau de rayon donné ? Largeur d'un demi-pixel de part et d'autre. */
    private static boolean ring(double r, double at) {
        return Math.abs(r - at) < 0.45d;
    }

    /**
     * L'étiquette : un disque d'ambre, cerné, avec son trou au centre.
     *
     * <p>Le cerne sombre n'est pas décoratif — sans lui, l'ambre touche directement le vinyle presque
     * noir, et le contraste brut fait baver l'étiquette quand l'objet est affiché à seize pixels
     * dans une case d'inventaire.
     */
    private static int label(double r, double dy) {
        if (r < 1.05d) {
            return EDGE;
        }
        if (r > 2.85d) {
            return blend(EDGE, AMBER[0], 0.35d);
        }
        if (r > 2.35d) {
            return AMBER[0];
        }
        // L'ambre s'éclaircit vers le haut, comme le vinyle.
        return blend(AMBER[1], AMBER[2], Math.clamp((1.6d - dy) / 3.2d, 0d, 1d));
    }

    // --- Les deux planches -------------------------------------------------

    /**
     * La planche de la mosaïque : à quoi ressemble l'atlas partagé.
     *
     * <p>Elle montre ce qu'un paragraphe explique mal — le rangement par étagères, la marge autour de
     * chaque case, et l'espace resté libre. Les proportions sont celles du code : marge de seize
     * pixels pour quatre niveaux de réduction, cases alignées sur seize.
     */
    private static BufferedImage mosaic() {
        int side = 720;
        int margin = 84;
        BufferedImage img = new BufferedImage(side, side + margin, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(new Color(0xFF101014, true));
        g.fillRect(0, 0, side, side + margin);

        // Les cases, telles qu'un rangement par étagères les poserait.
        int[][] cells = {
            {0, 0, 320, 240}, {320, 0, 240, 240}, {560, 0, 160, 160},
            {0, 240, 180, 320}, {180, 240, 260, 180}, {440, 240, 200, 200},
            {0, 560, 240, 140}, {240, 560, 140, 140},
        };
        int[] tints = {0xFF3C6E9E, 0xFFB4452F, 0xFF4E8A4A, 0xFF8A6BA8,
            0xFFDE7A1C, 0xFF2E7C7A, 0xFFA8894E, 0xFF9E4470};
        int pad = 16;
        for (int i = 0; i < cells.length; i++) {
            int[] c = cells[i];
            // La marge, dans la teinte de l'image mais délavée : c'est une recopie de ses bords.
            g.setColor(new Color(blend(tints[i], 0xFF101014, 0.55d), true));
            g.fillRect(c[0], c[1], c[2], c[3]);
            // L'image elle-même.
            g.setColor(new Color(tints[i], true));
            g.fillRect(c[0] + pad, c[1] + pad, c[2] - 2 * pad, c[3] - 2 * pad);
        }

        // Le quadrillage d'alignement : une ligne tous les cent soixante pixels.
        g.setColor(new Color(255, 255, 255, 26));
        g.setStroke(new BasicStroke(1f));
        for (int at = 0; at <= side; at += 160) {
            g.drawLine(at, 0, at, side);
            g.drawLine(0, at, side, at);
        }
        g.setColor(new Color(0xFFFFC857, true));
        g.setStroke(new BasicStroke(3f));
        g.drawRect(1, 1, side - 3, side - 3);

        g.setColor(new Color(0xFFFFC857, true));
        g.setFont(new Font("SansSerif", Font.PLAIN, 22));
        g.drawString("Une seule texture pour tous les tableaux du monde.", 14, side + 30);
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g.setColor(new Color(0xFF9A9AA6, true));
        g.drawString("Teinte pleine : l'image.   Teinte délavée : la marge recopiée.", 14, side + 54);
        g.drawString("Elle empêche une case de baver sur sa voisine, à tous les niveaux de réduction.",
                14, side + 74);
        g.dispose();
        return img;
    }

    /**
     * La planche du tableau : ce que le joueur voit sur son mur.
     *
     * <p>L'image montrée est synthétique — un couchant construit par le programme — et non une photo
     * du dossier de qui que ce soit. Un README doit pouvoir être régénéré sans dépendre du contenu
     * personnel de celui qui le régénère.
     */
    private static BufferedImage gallery() {
        int width = 880;
        int height = 560;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Le mur : de la pierre, en appareil décalé d'une rangée à l'autre.
        int block = 56;
        for (int row = 0; row * block < height; row++) {
            for (int column = -1; column * block < width + block; column++) {
                int x = column * block + (row % 2 == 0 ? 0 : block / 2);
                int shade = 0xFF4A4A52 + ((row * 7 + column * 13) % 5) * 0x00060608;
                g.setColor(new Color(shade, true));
                g.fillRect(x, row * block, block - 2, block - 2);
            }
        }
        // Un assombrissement vers les bords : l'œil doit aller au tableau, pas au mur.
        for (int i = 0; i < 120; i++) {
            g.setColor(new Color(0, 0, 0, 2));
            g.drawRect(i, i, width - 2 * i, height - 2 * i);
        }

        // Le tableau : quatre blocs sur trois, centré.
        int pw = 4 * block * 2;
        int ph = 3 * block * 2;
        int px = (width - pw) / 2;
        int py = (height - ph) / 2;

        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                double fx = x / (double) pw;
                double fy = y / (double) ph;
                int colour;
                if (fy < 0.62d) {
                    // Le ciel, du bleu profond au dessus vers l'orangé de l'horizon.
                    double t = fy / 0.62d;
                    colour = blend(0xFF1B2B54, 0xFFE8873A, Math.pow(t, 1.8d));
                    // Le soleil.
                    double dx = (fx - 0.68d) * pw;
                    double dy = (fy - 0.52d) * ph;
                    double r = Math.sqrt(dx * dx + dy * dy);
                    if (r < 46d) {
                        colour = blend(0xFFFFF0C0, colour, Math.min(1d, r / 46d));
                    }
                } else {
                    // Les collines, deux plans superposés.
                    double horizon = 0.62d + 0.06d * Math.sin(fx * 7.0d);
                    double second = 0.72d + 0.05d * Math.sin(fx * 4.0d + 1.2d);
                    if (fy < horizon) {
                        colour = 0xFF3A2C46;
                    } else if (fy < second) {
                        colour = 0xFF2A2036;
                    } else {
                        colour = blend(0xFF1A1426, 0xFF0E0A16, (fy - second) / (1d - second));
                    }
                }
                img.setRGB(px + x, py + y, colour);
            }
        }
        // La tranche sombre du tableau, comme en jeu.
        g.setColor(new Color(0xFF3A3028, true));
        g.setStroke(new BasicStroke(6f));
        g.drawRect(px - 3, py - 3, pw + 6, ph + 6);

        g.dispose();
        return img;
    }

    // --- Outillage ---------------------------------------------------------

    private static BufferedImage blank() {
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    private static void set(BufferedImage img, int x, int y, int argb) {
        if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) {
            img.setRGB(x, y, argb);
        }
    }

    private static void fill(BufferedImage img, int x, int y, int w, int h, int argb) {
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                set(img, x + dx, y + dy, argb);
            }
        }
    }

    /**
     * Cerne l'objet d'un contour sombre.
     *
     * <p>Sans lui, un objet de seize pixels se fond dans le fond d'une case d'inventaire, qui est
     * gris. C'est la règle la plus constante de la grammaire visuelle de Minecraft, et s'en écarter
     * se voit immédiatement.
     */
    private static void outline(BufferedImage img) {
        BufferedImage copy = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        copy.getGraphics().drawImage(img, 0, 0, null);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if ((copy.getRGB(x, y) >>> 24) != 0) {
                    continue;
                }
                boolean touches = opaque(copy, x - 1, y) || opaque(copy, x + 1, y)
                        || opaque(copy, x, y - 1) || opaque(copy, x, y + 1);
                if (touches) {
                    img.setRGB(x, y, EDGE);
                }
            }
        }
    }

    private static boolean opaque(BufferedImage img, int x, int y) {
        return x >= 0 && y >= 0 && x < 16 && y < 16 && (img.getRGB(x, y) >>> 24) != 0
                && img.getRGB(x, y) != EDGE;
    }

    /** Agrandit au plus proche voisin : le lissage tuerait la netteté d'une texture de seize pixels. */
    private static BufferedImage blow(BufferedImage source, int factor) {
        int w = source.getWidth() * factor;
        int h = source.getHeight() * factor;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, source.getRGB(x / factor, y / factor));
            }
        }
        return out;
    }

    private static int blend(int from, int to, double t) {
        t = Math.clamp(t, 0d, 1d);
        int a = (int) (((from >>> 24) & 0xFF) * (1 - t) + ((to >>> 24) & 0xFF) * t);
        int r = (int) (((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
        int g = (int) (((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
        int b = (int) ((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private AtelierTex() {}
}
