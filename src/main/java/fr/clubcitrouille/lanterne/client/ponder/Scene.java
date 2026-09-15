package fr.clubcitrouille.lanterne.client.ponder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * Le scénario : ce qui se passe, et quand.
 *
 * <h2>Une scène est une fonction du temps, et rien d'autre</h2>
 *
 * <p>C'est la seule décision d'architecture de ce paquet, et tout le reste en découle. Chaque
 * figurant porte son emploi du temps — l'instant où il paraît, celui où il s'en va, le trajet qu'il
 * suit entre les deux. Dessiner l'instant <i>t</i>, c'est évaluer tout le monde en <i>t</i>. Il n'y a
 * pas d'état qui s'accumule, pas de monde qu'on modifie, rien à défaire.
 *
 * <h2>Ce que cela règle, et que Ponder ne règle pas</h2>
 *
 * <p>Ponder, dont ce paquet s'inspire, fait l'inverse : ses instructions <em>modifient</em> un faux
 * niveau, tick après tick. Le prix est écrit dans son propre code, et il est raide —
 * {@code PonderScene.seekToTime} commence par&nbsp;:
 *
 * <pre>if (time &lt; currentTime)
 *     throw new IllegalStateException("Cannot seek backwards. Rewind first.");</pre>
 *
 * <p><b>On ne peut pas reculer.</b> Reculer de cinq ticks, chez lui, c'est remettre le monde à zéro
 * depuis un cliché et rejouer toute la scène en accéléré. Et comme un saut coûte le temps qu'on
 * saute, sa barre de progression n'accepte le clic que sur des repères posés à la main par l'auteur —
 * jamais à un instant quelconque.
 *
 * <p>Ici, reculer coûte exactement ce que coûte avancer, c'est-à-dire rien. La barre accepte
 * n'importe quel instant, on peut tirer dessus, faire marche arrière, mettre en pause au milieu d'un
 * mouvement. Ce n'est pas une prouesse : c'est ce qu'on obtient gratuitement en refusant de tenir un
 * état.
 *
 * <p>Il y a un second effet, moins visible et plus précieux : <b>une scène ne peut pas se
 * désynchroniser</b>. Un système à état a des bogues d'histoire — un bloc qui reste posé parce qu'un
 * ordre est passé deux fois. Ici l'image de l'instant <i>t</i> ne dépend que de <i>t</i>. C'est ce
 * qu'on veut quand on écrit une animation qu'on n'a pas le droit de lancer pour la regarder.
 *
 * <h2>Écrire une scène</h2>
 *
 * <p>Un curseur avance dans le temps, et les verbes déposent des choses à l'endroit où il est. Une
 * seule règle à retenir, et elle n'a pas d'exception :
 *
 * <p><b>Trois verbes font avancer l'horloge — {@link #hold}, {@link #raise} et {@link #fly} — parce
 * que leur durée est leur sujet. Tous les autres déposent et ne bougent rien.</b>
 *
 * <p>Une surbrillance ou une bulle de texte ne fait donc pas attendre : elle vit sa durée pendant que
 * la suite se déroule. C'est la même idée que chez Ponder, où {@code idle} est la seule instruction
 * bloquante de tout le vocabulaire, et c'est ce qui rend les scénarios lisibles — on écrit une
 * séquence, on obtient du parallélisme.
 *
 * <h2>Les coordonnées</h2>
 *
 * <p>Un entier nomme une case, et <b>désigne son centre</b>. La case {@code (0,0,0)} occupe donc
 * l'espace de {@code -0,5} à {@code +0,5}. C'est moins conforme à Minecraft, où un entier désigne un
 * coin, et bien plus commode pour écrire : viser une case, c'est écrire ses trois entiers, sans
 * demi-blocs qui traînent.
 *
 * <p>{@code X} va vers l'est, {@code Y} vers le haut, {@code Z} vers le sud, comme dans le jeu.
 */
public final class Scene {
    /** Chute d'un bloc qui se pose : assez pour qu'on la voie, assez peu pour qu'on n'attende pas. */
    private static final float DROP = 7f;
    /** Durée de l'effacement d'un bloc qui s'en va. */
    private static final float LIFT = 6f;
    /** Durée du fondu d'une nappe, d'une bulle ou d'une surbrillance à ses deux bouts. */
    private static final float EDGE = 5f;
    /** Ce qu'on laisse après la dernière image pour que le regard finisse sa phrase. */
    private static final int TAIL = 24;

    // --- La donnée -----------------------------------------------------------

    /**
     * Un figurant : un bloc, ou une nappe.
     *
     * <p>Les deux dans le même type, et c'est voulu : ils partagent une liste, donc un tri par
     * profondeur, donc un ordre de recouvrement juste. Les séparer aurait rendu une nappe toujours
     * devant ou toujours derrière les blocs qui l'entourent, ce qui se voit immédiatement sur un
     * portail — dont la nappe est précisément <em>dans</em> son cadre.
     */
    private static final class Piece {
        /** Flottants, et non entiers : une nappe se pose parfois sur la <em>face</em> d'un bloc. */
        final float x;
        final float y;
        final float z;
        final float deep;
        /** La pile à dessiner, ou {@code null} quand ce figurant est une nappe. */
        final ItemStack look;
        final boolean alongX;
        final int colour;
        final float in;
        float out = Float.MAX_VALUE;

        Piece(float x, float y, float z, ItemStack look, boolean alongX, int colour, float in) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.deep = Stage.depth(x, y, z);
            this.look = look;
            this.alongX = alongX;
            this.colour = colour;
            this.in = in;
        }
    }

    /** Une légende. Elle court jusqu'à la suivante, et pose un repère de chapitre. */
    public record Word(int from, String key) {}

    /** Une étiquette accrochée à un point du monde. */
    public record Tag(float from, float to, float x, float y, float z, String key) {}

    private record Halo(float from, float to, int x, int y, int z, int colour) {}

    private record Flier(float from, float to, ItemStack look,
                         float x1, float y1, float z1, float x2, float y2, float z2) {}

    private record Spark(float at, float x, float y, float z, int colour) {}

    private record Shot(float from, float to, float x, float y, float z, float zoom) {}

    private final String key;
    private final ItemStack icon;

    private final List<Piece> pieces = new ArrayList<>();
    private final List<Word> words = new ArrayList<>();
    private final List<Tag> tags = new ArrayList<>();
    private final List<Halo> haloes = new ArrayList<>();
    private final List<Flier> fliers = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private final List<Shot> shots = new ArrayList<>();

    /** La case occupée à l'instant du curseur, pour que {@link #swap} sache qui remplacer. */
    private final Map<Long, Piece> standing = new HashMap<>();

    private int now;
    private int length;
    private int[] marks = new int[0];

    private Scene(String key, ItemStack icon) {
        this.key = key;
        this.icon = icon;
    }

    /**
     * Ouvre un scénario.
     *
     * @param key  le tronc des clés de langue : le titre sera {@code lanterne.guide.<key>.titre}
     * @param icon la pile montrée dans la liste des chapitres
     */
    public static Scene named(String key, ItemStack icon) {
        return new Scene(key, icon);
    }

    // --- Les verbes ----------------------------------------------------------

    /** Attend. Le seul verbe dont c'est tout le propos. */
    public Scene hold(int ticks) {
        this.now += Math.max(0, ticks);
        return this;
    }

    /** Pose la caméra sans mouvement. À employer une fois, en tête de scénario. */
    public Scene aim(float x, float y, float z, float zoom) {
        this.shots.add(new Shot(this.now, this.now, x, y, z, zoom));
        return this;
    }

    /**
     * Déplace la caméra.
     *
     * <p>Elle glisse, elle ne saute pas : c'est {@link Ease#smooth} qui s'en charge, la seule courbe
     * dont les deux bouts sont immobiles. Une caméra qui démarre sec donne mal au cœur, et c'est le
     * défaut le plus courant des guides animés.
     */
    public Scene pan(float x, float y, float z, float zoom, int ticks) {
        this.shots.add(new Shot(this.now, this.now + Math.max(1, ticks), x, y, z, zoom));
        return this;
    }

    /** La légende du bas, et un repère de chapitre là où elle commence. */
    public Scene say(String suffix) {
        this.words.add(new Word(this.now, "lanterne.guide." + this.key + "." + suffix));
        return this;
    }

    /** Un bloc paraît, en tombant de sa hauteur. */
    public Scene put(int x, int y, int z, ItemStack look) {
        Piece piece = new Piece(x, y, z, look, false, 0, this.now);
        this.pieces.add(piece);
        this.standing.put(cell(x, y, z), piece);
        return this;
    }

    /**
     * Une suite de blocs se pose, l'un après l'autre. <b>Fait avancer l'horloge.</b>
     *
     * @param cells les cases, dans l'ordre où on veut les voir arriver
     * @param gap   l'écart entre deux poses, en ticks
     */
    public Scene raise(int[][] cells, ItemStack look, int gap) {
        int step = Math.max(1, gap);
        for (int[] c : cells) {
            put(c[0], c[1], c[2], look);
            this.now += step;
        }
        return this;
    }

    /**
     * Échange le bloc d'une case contre un autre.
     *
     * <p>L'ancien s'efface net, sans son animation de départ : on veut que le regard voie un
     * <em>remplacement</em>, et deux animations qui se chevauchent le feraient lire comme une
     * disparition suivie d'une apparition, ce qui n'est pas le même geste.
     */
    public Scene swap(int x, int y, int z, ItemStack look) {
        Piece old = this.standing.get(cell(x, y, z));
        if (old != null) {
            old.out = this.now;
        }
        return put(x, y, z, look);
    }

    /** Un bloc s'en va, en rétrécissant. */
    public Scene take(int x, int y, int z) {
        Piece old = this.standing.remove(cell(x, y, z));
        if (old != null) {
            old.out = this.now + LIFT;
        }
        return this;
    }

    /**
     * Une surface plate paraît en fondu, et y reste : la nappe d'un portail, une toile sur un mur.
     *
     * <p>La profondeur est un flottant pour cette raison précise — une toile se pose sur la
     * <em>face</em> d'un mur, donc à un demi-bloc du centre de sa case, pas au centre.
     */
    public Scene sheet(float x, float y, float z, boolean alongX, int colour) {
        this.pieces.add(new Piece(x, y, z, null, alongX, colour, this.now));
        return this;
    }

    /** Une surbrillance sur une case, qui bat doucement pendant sa durée. */
    public Scene halo(int x, int y, int z, int colour, int ticks) {
        this.haloes.add(new Halo(this.now, this.now + Math.max(1, ticks), x, y, z, colour));
        return this;
    }

    /** Une étiquette accrochée à un point, avec son trait de rappel. */
    public Scene tag(float x, float y, float z, String suffix, int ticks) {
        this.tags.add(new Tag(this.now, this.now + Math.max(1, ticks), x, y, z,
                "lanterne.guide." + this.key + "." + suffix));
        return this;
    }

    /**
     * Un objet traverse la scène. <b>Fait avancer l'horloge.</b>
     *
     * <p>C'est le verbe qui porte les gestes : le briquet qui va vers le cœur, le disque qui entre
     * dans le graveur. Un geste montré vaut une phrase épargnée, et c'est tout le propos.
     */
    public Scene fly(ItemStack look, float x1, float y1, float z1,
                     float x2, float y2, float z2, int ticks) {
        int span = Math.max(1, ticks);
        this.fliers.add(new Flier(this.now, this.now + span, look, x1, y1, z1, x2, y2, z2));
        this.now += span;
        return this;
    }

    /** Une gerbe d'étincelles part d'un point. */
    public Scene spark(float x, float y, float z, int colour) {
        this.sparks.add(new Spark(this.now, x, y, z, colour));
        return this;
    }

    /**
     * Ferme le scénario : trie les figurants et mesure la durée.
     *
     * <p>Le tri par profondeur est fait <b>ici et pas à chaque image</b>, ce qui est possible parce
     * que la caméra ne tourne jamais : la profondeur d'une case ne dépend que de la case. Un système
     * qui ferait tourner la caméra devrait retrier à chaque image, et ce serait le poste le plus cher
     * de tout l'écran.
     */
    public Scene seal() {
        this.pieces.sort((a, b) -> Float.compare(a.deep, b.deep));

        int end = this.now;
        for (Piece p : this.pieces) {
            end = Math.max(end, (int) (p.in + DROP));
            if (p.out != Float.MAX_VALUE) {
                end = Math.max(end, (int) p.out);
            }
        }
        for (Tag t : this.tags) {
            end = Math.max(end, (int) t.to());
        }
        for (Halo h : this.haloes) {
            end = Math.max(end, (int) h.to());
        }
        for (Shot s : this.shots) {
            end = Math.max(end, (int) s.to());
        }
        this.length = end + TAIL;

        this.marks = new int[this.words.size()];
        for (int i = 0; i < this.marks.length; i++) {
            this.marks[i] = this.words.get(i).from();
        }
        return this;
    }

    // --- Les cases -----------------------------------------------------------

    /** Toutes les cases d'un pavé, en balayant d'abord la hauteur. */
    public static int[][] box(int x0, int y0, int z0, int x1, int y1, int z1) {
        List<int[]> out = new ArrayList<>();
        for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
                    out.add(new int[] {x, y, z});
                }
            }
        }
        return out.toArray(new int[0][]);
    }

    /**
     * Le pourtour d'un rectangle vertical, dans l'ordre où une personne le bâtirait.
     *
     * <p>La rangée du bas d'abord, puis les deux montants en parallèle, puis le linteau. Ce n'est pas
     * de la coquetterie : un cadre qui se remplirait dans le désordre ne montrerait pas <em>comment
     * on le fait</em>, et c'est la seule chose que cette animation a à dire.
     *
     * <p>Les quatre coins sont inclus. Ils ne comptent pas pour l'algorithme de cadre de vanilla —
     * voir {@code content/waypoint/Frame.java} — mais personne ne bâtit un portail sans eux.
     */
    public static int[][] ring(int x0, int y0, int x1, int y1, int z) {
        List<int[]> out = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            out.add(new int[] {x, y0, z});
        }
        for (int y = y0 + 1; y < y1; y++) {
            out.add(new int[] {x0, y, z});
            out.add(new int[] {x1, y, z});
        }
        for (int x = x0; x <= x1; x++) {
            out.add(new int[] {x, y1, z});
        }
        return out.toArray(new int[0][]);
    }

    private static long cell(int x, int y, int z) {
        return ((long) (x + 512) << 42) | ((long) (y + 512) << 21) | (z + 512);
    }

    // --- La lecture ----------------------------------------------------------

    public String key() {
        return this.key;
    }

    public ItemStack icon() {
        return this.icon;
    }

    public int length() {
        return this.length;
    }

    /** Les instants où une légende commence : les chapitres de la barre du bas. */
    public int[] marks() {
        return this.marks;
    }

    public List<Tag> tagList() {
        return this.tags;
    }

    /** La clé de langue de la légende en cours, ou {@code null} avant la première. */
    public String word(float t) {
        String found = null;
        for (Word w : this.words) {
            if (w.from() > t) {
                break;
            }
            found = w.key();
        }
        return found;
    }

    /**
     * Combien une chose vivant entre deux instants doit se montrer, de 0 à 1.
     *
     * <p>Un fondu aux deux bouts, et jamais d'apparition franche : ce qui paraît d'un coup arrache le
     * regard à ce qu'il était en train de suivre.
     */
    public static float window(float t, float from, float to) {
        if (t < from || t > to) {
            return 0f;
        }
        return Math.min(Ease.over(t, from, from + EDGE), 1f - Ease.over(t, to - EDGE, to));
    }

    /**
     * Règle la caméra pour l'instant demandé.
     *
     * <p>Chaque prise est appliquée par-dessus la précédente : une prise terminée écrase exactement,
     * la prise en cours interpole depuis l'endroit où la précédente a laissé la caméra. Le résultat
     * est une chaîne de points de passage, et elle ne dépend que de <i>t</i> — reculer la remonte
     * aussi bien qu'avancer la descend.
     */
    public void place(Stage stage, float t) {
        float x = 0f;
        float y = 0f;
        float z = 0f;
        float zoom = 1f;
        for (Shot s : this.shots) {
            // Strictement inférieur, et non inférieur ou égal : la prise d'ouverture est posée à
            // l'instant zéro et doit donc s'appliquer à l'instant zéro. Avec un « ou égal », la
            // toute première image — et chaque retour au début, donc chaque clic sur « reprendre » —
            // montrerait la caméra à sa position par défaut au lieu de celle qu'on a écrite.
            if (t < s.from()) {
                break;
            }
            float k = Ease.smooth(Ease.over(t, s.from(), s.to()));
            x = Ease.lerp(x, s.x(), k);
            y = Ease.lerp(y, s.y(), k);
            z = Ease.lerp(z, s.z(), k);
            zoom = Ease.lerp(zoom, s.zoom(), k);
        }
        stage.aim(x, y, z, zoom);
    }

    /** Le décor : les figurants, puis ce qui les survole. */
    public void draw(GuiGraphicsExtractor graphics, Stage stage, float t) {
        for (Piece p : this.pieces) {
            if (t < p.in || t >= p.out) {
                continue;
            }
            if (p.look == null) {
                drawPane(graphics, stage, p, t);
            } else {
                drawBlock(graphics, stage, p, t);
            }
        }
        for (Halo h : this.haloes) {
            float fade = window(t, h.from(), h.to());
            if (fade <= 0.02f) {
                continue;
            }
            // Le battement : lent, et jamais jusqu'à l'extinction. Une surbrillance qui clignote
            // franchement se lit comme une alarme ; celle-ci respire.
            float beat = 0.62f + 0.38f * (float) Math.sin(t * 0.32d);
            stage.cage(graphics, h.x(), h.y(), h.z(), tint(h.colour(), fade * beat), 2f);
        }
        for (Flier f : this.fliers) {
            if (t < f.from() || t > f.to()) {
                continue;
            }
            float k = Ease.smooth(Ease.over(t, f.from(), f.to()));
            float x = Ease.lerp(f.x1(), f.x2(), k);
            float y = Ease.lerp(f.y1(), f.y2(), k);
            float z = Ease.lerp(f.z1(), f.z2(), k);
            // Une cloche, pour que le trajet ait l'air lancé et non tiré à la ficelle.
            stage.block(graphics, f.look(), x, y + Ease.pulse(k) * 0.75f, z, 0.62f, 0f);
        }
        for (Spark s : this.sparks) {
            drawSpark(graphics, stage, s, t);
        }
    }

    private void drawBlock(GuiGraphicsExtractor graphics, Stage stage, Piece p, float t) {
        float size = 1f;
        float lift = 0f;
        float age = t - p.in;
        if (age < DROP) {
            float k = age / DROP;
            lift = (1f - Ease.out(k)) * 1.15f;
            // Le dépassement de la courbe fait que le bloc s'écrase un instant en touchant : c'est
            // deux images, et c'est ce qui lui donne du poids.
            size = Ease.lerp(0.74f, 1f, Ease.back(k));
        }
        float left = p.out - t;
        if (left < LIFT) {
            float k = Ease.in(1f - left / LIFT);
            size *= 1f - k;
            lift += k * 0.4f;
        }
        stage.block(graphics, p.look, p.x, p.y, p.z, size, lift);
    }

    private void drawPane(GuiGraphicsExtractor graphics, Stage stage, Piece p, float t) {
        float fade = Ease.out(Ease.over(t, p.in, p.in + 12f));
        if (p.out != Float.MAX_VALUE) {
            fade *= 1f - Ease.over(t, p.out - LIFT, p.out);
        }
        if (fade <= 0.02f) {
            return;
        }
        // Un léger ondoiement vertical, déphasé par la case : sans lui, une nappe de six cases est
        // un aplat, et un aplat ne ressemble pas à un portail.
        float wave = 0.5f + 0.5f * (float) Math.sin(t * 0.18d + (p.x + p.y) * 1.1d);
        stage.pane(graphics, p.x, p.y, p.z, p.alongX,
                tint(p.colour, fade * (0.55f + 0.30f * wave)),
                tint(p.colour, fade * (0.85f - 0.25f * wave)));
    }

    /**
     * Une gerbe qui part d'un point.
     *
     * <p>Huit brins, dont les directions sont tirées d'une graine faite de la position : c'est
     * aléatoire à l'œil et rigoureusement reproductible, ce qui est exigé ici — une scène qu'on
     * rembobine doit redonner la même image, faute de quoi la barre de progression mentirait.
     */
    private void drawSpark(GuiGraphicsExtractor graphics, Stage stage, Spark s, float t) {
        float age = t - s.at();
        if (age < 0f || age > 14f) {
            return;
        }
        float k = age / 14f;
        int seed = Float.floatToIntBits(s.x() * 31f + s.y() * 7f + s.z());
        for (int i = 0; i < 8; i++) {
            int h = seed * 1103515245 + i * 12345;
            float ax = ((h >> 8 & 0xFF) / 127.5f - 1f) * 0.9f;
            float az = ((h >> 16 & 0xFF) / 127.5f - 1f) * 0.9f;
            float ay = 0.35f + (h >> 24 & 0xFF) / 255f * 0.8f;
            float reach = Ease.out(k);
            float x = s.x() + ax * reach;
            float z = s.z() + az * reach;
            float y = s.y() + ay * reach - 1.3f * k * k;
            stage.dot(graphics, x, y, z, Math.max(1f, stage.zoom() * 1.6f * (1f - k)),
                    tint(s.colour(), 1f - k * k));
        }
    }

    /** Une couleur {@code 0xRRGGBB} rendue opaque à la mesure demandée. */
    public static int tint(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
