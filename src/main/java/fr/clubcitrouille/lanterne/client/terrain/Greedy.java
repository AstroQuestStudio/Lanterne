package fr.clubcitrouille.lanterne.client.terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3fc;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.QuadInstance;

import net.minecraft.core.Direction;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.ARGB;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La fusion de faces elle-même — le morceau qui manquait depuis 9+ passes précédentes sur ce
 * chantier. Voir {@link TerrainVertexFormat} pour le format de sommet qui la rend possible sans
 * étirer les textures, et {@code notes/rendu-terrain-vs-sodium.md} pour l'historique complet.
 *
 * <h2>Le périmètre : élargi une fois de {@code UP} seul à {@code UP}+{@code DOWN}</h2>
 *
 * <p>Les faces {@link Direction#UP} ET {@link Direction#DOWN} de la couche {@link ChunkSectionLayer#SOLID},
 * fusionnées le long de l'axe X local, à l'intérieur d'une même rangée (Y, Z, ET direction fixes — voir
 * {@link Pending#direction} et la clé de regroupement dans {@link #flush}, qui empêche explicitement un
 * plafond DOWN de se mélanger avec un sol UP qui occuperait par coïncidence le même (Y, Z)). Le premier
 * jalon de ce chantier ne couvrait que {@code UP} ; {@code DOWN} a été ajouté ensuite sans généralisation
 * de {@link TerrainVertexFormat#putMergedRunAlongX} — sa doc de méthode annonçait déjà gérer « les faces
 * horizontales (UP/DOWN) », vérifié vrai : une face DOWN varie en X et Z exactement comme une face UP (seul
 * Y diffère, fixé à 0 plutôt qu'à 1), donc le même appariement lo/hi par Z convient sans changement.
 *
 * <p><b>Ce qui reste délibérément hors périmètre</b> : les faces latérales ({@code NORTH}/{@code SOUTH}/
 * {@code EAST}/{@code WEST}) et la fusion le long de l'axe Z (un vrai balayage 2D). Pas ajoutées cette
 * passe : une face {@code NORTH}/{@code SOUTH} varie en X et Y (Z fixe) — l'appariement lo/hi de
 * {@code putMergedRunAlongX}, actuellement câblé sur Z, apparierait alors n'importe quel sommet avec
 * n'importe quel autre (Z quasi constant des deux côtés) au lieu de rejeter proprement ou d'apparier
 * juste — une vraie généralisation (détecter dynamiquement si c'est Y ou Z qui varie entre les deux
 * sommets « bas ») est nécessaire AVANT d'ouvrir ces directions, pas juste retirer le filtre de
 * direction. Sans suite de tests automatisés dans ce dépôt (aucun test JUnit n'existe ici), la seule
 * vérification possible resterait visuelle (Snap) — insuffisante pour prouver l'absence d'un mauvais
 * appariement de sommets qui ne se verrait que sur une géométrie non uniforme (peu probable sur les
 * mondes de test plats utilisés jusqu'ici). {@code EAST}/{@code WEST} sont encore plus loin : leur X ne
 * varie PAS (rejetées net par le test {@code maxX - minX < epsilon} de {@code putMergedRunAlongX}), il
 * leur faudrait un axe de fusion différent (Z), donc un chemin de code séparé, pas une simple extension
 * du filtre de direction. La consigne du chantier reste explicite là-dessus : vérifier un cas simple
 * avant les cas complexes, pas les livrer tous à l'aveugle.
 *
 * <h2>Comment ça s'articule avec {@code SectionCompilerMixin}</h2>
 *
 * <p>{@code SectionCompilerMixin} appelle {@link #accept} à la place d'émettre directement. Un quad
 * UP/SOLID ou DOWN/SOLID est mis de côté (pas encore écrit dans le tampon) ; tout le reste (faces
 * latérales, CUTOUT, TRANSLUCENT) part immédiatement par {@link TerrainVertexFormat#putQuad} comme avant
 * cette passe — le périmètre non couvert ici n'est donc pas dégradé, juste pas encore fusionné.
 *
 * <p>{@link #flush} est appelé une fois par section, juste avant que son tampon SOLID ne soit figé
 * ({@code BufferBuilder.build()}) — voir le point d'injection exact dans {@code SectionCompilerMixin}.
 * Il regroupe les quads mis de côté par rangée (direction, Y, Z), les trie par X, fusionne les runs
 * consécutifs qui partagent la même clé ({@link #mergeable}), et émet soit un quad unique (run de
 * longueur 1) soit un quad fusionné ({@link TerrainVertexFormat#putMergedRunAlongX}) — avec repli
 * individuel si cette dernière refuse la géométrie.
 *
 * <h2>Sécurité de fusion : à l'identique, jamais à l'approximatif</h2>
 *
 * <p>{@link #mergeable} exige une correspondance EXACTE des bornes de sprite (même texture), de la
 * couleur finale ET de la lumière des 4 sommets, ET de la géométrie locale des 4 coins du quad
 * ({@link BakedQuad#position(int)}) entre les deux quads comparés. Ce dernier point ferme un risque
 * resté latent plusieurs passes : deux blocs différents (mod tiers, ou une dalle vs un bloc plein en
 * vanilla) peuvent par coïncidence partager sprite/couleur/lumière identiques tout en ayant une face
 * UP de forme différente (hauteur, empan) — sans comparer la géométrie, ces deux quads auraient pu
 * fusionner avec un indexage de sommet incohérent, {@code putMergedRunAlongX} n'utilisant que les
 * positions de {@code first}/{@code last} du run (les quads intermédiaires ne sont vérifiés qu'en
 * apparence, jamais en position). Un seul sommet qui diffère sur n'importe lequel de ces quatre
 * critères (occlusion ambiante différente au bord d'un bloc voisin, teinte de biome légèrement
 * différente, forme de face différente) bloque la fusion pour cette paire — un repli sûr : la pire
 * conséquence d'un refus de fusion est un gain plus faible que possible, jamais un artefact visuel.
 */
public final class Greedy {
    /**
     * {@code LANTERNE_GREEDY_MESH=1} active la fusion.
     *
     * <h2>Historique du carré gris/jaune — cause confirmée et corrigée</h2>
     *
     * <p>Deux passes précédentes ont vu un sol de neige fusionné rendu en larges carrés GRIS/JAUNES
     * uniformes. Une première cause réelle (discontinuité de dérivée d'écran au bord de tuile) a été
     * corrigée dans {@code terrain.fsh}, mais le défaut persistait. Une passe ultérieure a épuisé
     * TOUTES les pistes côté shader (bornes de sprite lues correctement, {@code local}/{@code mergedU}
     * calculés et enroulés correctement — vérifié par un shader de diagnostic en bandes dures montrant
     * bien N répétitions par quad fusionné de longueur N — dérivées explicites non dégénérées, le
     * "nearest snapping" de {@code sampleNearest} innocenté en échantillonnant directement via
     * {@code textureGrad}) sans trouver la cause, parce qu'elle n'était PAS côté shader.
     *
     * <p><b>La vraie cause</b> (confirmée par {@code javap -c} sur {@code ModelBlockRenderer} du vrai
     * jar patché, puis par comparaison de pixels exacts entre rendu fusionné et non fusionné au même
     * endroit) : {@link Pending} gardait une référence VIVANTE vers le {@link QuadInstance} passé à
     * {@link #accept}. Or {@code ModelBlockRenderer.quadInstance} est un champ {@code private final},
     * UNE SEULE instance allouée au constructeur et MUTÉE EN PLACE pour chaque quad de la section avant
     * d'être "put" — {@link #flush}, appelé une seule fois à la fin de la compilation de la section,
     * relisait donc au moment de la fusion la couleur/lumière du DERNIER quad traité par le thread pour
     * TOUS les quads en attente, pas celle du quad réellement mis de côté. Ça expliquait à la fois la
     * teinte fausse (mesurée : environ la moitié de la luminosité attendue, uniforme sur toute la zone
     * fusionnée d'une section — pas un dégradé, signe d'une valeur figée unique) ET pourquoi
     * {@link #mergeable} ne refusait jamais rien sur une différence de lumière (les deux côtés de la
     * comparaison étaient littéralement le même objet au moment de la lecture). Corrigé en
     * instantanéisant couleur et lumière des 4 sommets dans {@link #accept}, avant que
     * {@code ModelBlockRenderer} ne mute son objet partagé pour le quad suivant — voir le Javadoc de
     * {@link Pending}.
     */
    private static final boolean ENABLED = "1".equals(System.getenv("LANTERNE_GREEDY_MESH"));

    /**
     * Un quad mis de côté, en attendant de savoir avec quoi le fusionner.
     *
     * <p><b>{@code colors}/{@code lights} sont un INSTANTANÉ, jamais une référence vivante vers
     * {@link QuadInstance}.</b> Vérifié par {@code javap -c} sur le vrai jar patché
     * ({@code ModelBlockRenderer}) : {@code quadInstance} y est un champ {@code private final}, une
     * SEULE instance allouée au constructeur, MUTÉE EN PLACE pour CHAQUE quad (via
     * {@code BlockModelLighter.prepareQuadFlat}/{@code prepareQuadAmbientOcclusion}) juste avant
     * d'être "put". C'était la vraie cause du carré gris/jaune (voir
     * {@code notes/etat-fusion-faces-20260918.md}) : une passe précédente gardait ici une référence
     * vivante vers cet objet partagé — {@link #flush} la relisait bien plus tard, après que
     * BEAUCOUP d'autres quads de la section aient muté ce même objet, lisant donc la couleur/lumière
     * du DERNIER quad traité par le thread, pas celle du quad réellement mis en attente. Ça expliquait
     * aussi pourquoi {@link #mergeable} ne bloquait jamais rien sur une différence de lumière : les
     * deux côtés de la comparaison étaient littéralement le même objet au moment de la lecture.
     */
    private static final class Pending {
        final float x;
        final float y;
        final float z;
        final Direction direction;
        final BakedQuad quad;
        final int[] colors;
        final int[] lights;

        Pending(float x, float y, float z, Direction direction, BakedQuad quad, int[] colors, int[] lights) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.direction = direction;
            this.quad = quad;
            this.colors = colors;
            this.lights = lights;
        }
    }

    /** État accumulé pour UNE section en cours de compilation sur CE thread. */
    private static final class State {
        BufferBuilder solidBuffer;
        final List<Pending> pendingQuads = new ArrayList<>();
        long quadsIn;
        long quadsOut;
    }

    /**
     * Compilation multithreadée (voir {@code SectionRenderDispatcher$TracingExecutor}, déjà établi
     * dans les notes de ce chantier) : un état par thread, jamais partagé, jamais un champ d'instance
     * sur {@code SectionCompiler} — cette classe peut être réutilisée en parallèle par plusieurs
     * tâches de compilation à la fois.
     */
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    /** Compteurs cumulés, tous threads confondus — voir {@link #report()}. */
    private static final java.util.concurrent.atomic.AtomicLong TOTAL_IN =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong TOTAL_OUT =
            new java.util.concurrent.atomic.AtomicLong();

    private Greedy() {}

    /**
     * Propose un quad à la fusion. Renvoie {@code true} s'il a été mis de côté (l'appelant ne doit
     * RIEN écrire lui-même) ; {@code false} s'il est hors périmètre et doit être émis immédiatement
     * par l'appelant, comme avant cette passe.
     */
    public static boolean accept(BufferBuilder buffer, float x, float y, float z, BakedQuad quad,
            QuadInstance instance) {
        if (!ENABLED) {
            return false;
        }
        Direction direction = quad.direction();
        if (direction != Direction.UP && direction != Direction.DOWN) {
            return false;
        }
        if (quad.materialInfo().layer() != ChunkSectionLayer.SOLID) {
            return false;
        }
        // Instantané IMMÉDIAT — voir le Javadoc de Pending pour pourquoi ceci ne doit JAMAIS être
        // remplacé par une simple référence vers `instance`.
        int lightEmission = quad.materialInfo().lightEmission();
        int[] colors = new int[4];
        int[] lights = new int[4];
        for (int i = 0; i < 4; i++) {
            colors[i] = ARGB.multiply(instance.getColor(i), quad.bakedColors().color(i));
            lights[i] = instance.getLightCoordsWithEmission(i, lightEmission);
        }

        State state = STATE.get();
        state.solidBuffer = buffer;
        state.pendingQuads.add(new Pending(x, y, z, direction, quad, colors, lights));
        return true;
    }

    /**
     * Fusionne et vide les quads mis de côté pour la section en cours sur ce thread, en les écrivant
     * dans le tampon SOLID capturé par {@link #accept}. À appeler une fois par section, après le
     * dernier {@link #accept} et AVANT que le tampon ne soit figé — voir {@code SectionCompilerMixin}
     * pour le point d'injection exact.
     */
    public static void flush() {
        State state = STATE.get();
        if (state.pendingQuads.isEmpty()) {
            return;
        }
        BufferBuilder buffer = state.solidBuffer;

        // Regroupe par rangée (direction, Y, Z fixes) — la fusion de ce jalon ne balaie que X à
        // l'intérieur d'une rangée. La direction fait PARTIE de la clé : sans elle, un plafond DOWN et
        // un sol UP qui occuperaient par coïncidence le même (Y, Z) local (sections différentes ou
        // géométrie non standard) se retrouveraient dans le même groupe de tri, et rien dans le tri par
        // X seul ne les distinguerait avant `mergeable()` — qui les bloquerait bien via la comparaison
        // de géométrie (voir son Javadoc), mais autant ne jamais les faire cohabiter dans le même run.
        // Clé entière : x/y/z passés à putBlockBakedQuad sont les coordonnées locales d'un bloc dans la
        // section, toujours entières en valeur (même si portées en float).
        Map<Long, List<Pending>> rows = new HashMap<>();
        for (Pending p : state.pendingQuads) {
            long key = (p.direction == Direction.UP ? 0L : 1L) << 32
                    | (Math.round(p.y) & 0xFFFFL) << 16 | (Math.round(p.z) & 0xFFFFL);
            rows.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        long in = state.pendingQuads.size();
        long out = 0;

        for (List<Pending> row : rows.values()) {
            row.sort(Comparator.comparingDouble(p -> p.x));
            int i = 0;
            while (i < row.size()) {
                int j = i + 1;
                while (j < row.size()
                        && Math.round(row.get(j).x - row.get(j - 1).x) == 1
                        && mergeable(row.get(j - 1), row.get(j))) {
                    j++;
                }
                int runLength = j - i;
                Pending first = row.get(i);
                if (runLength == 1) {
                    TerrainVertexFormat.putQuad(buffer, first.x, first.y, first.z, first.quad,
                            first.colors, first.lights);
                    out++;
                } else {
                    Pending last = row.get(j - 1);
                    boolean merged = TerrainVertexFormat.putMergedRunAlongX(buffer,
                            first.x, first.y, first.z, first.quad, first.colors, first.lights,
                            last.x, last.y, last.z, last.quad, last.colors, last.lights, runLength);
                    if (merged) {
                        out++;
                    } else {
                        // Repli sûr : la géométrie ne correspondait pas aux hypothèses vérifiées de
                        // putMergedRunAlongX (voir son Javadoc) — émet chaque quad du run séparément
                        // plutôt que de risquer une géométrie fausse.
                        for (int k = i; k < j; k++) {
                            Pending p = row.get(k);
                            TerrainVertexFormat.putQuad(buffer, p.x, p.y, p.z, p.quad, p.colors, p.lights);
                            out++;
                        }
                    }
                }
                i = j;
            }
        }

        state.pendingQuads.clear();
        state.solidBuffer = null;
        state.quadsIn += in;
        state.quadsOut += out;
        TOTAL_IN.addAndGet(in);
        TOTAL_OUT.addAndGet(out);
    }

    /**
     * Deux quads consécutifs le long de X peuvent-ils fusionner sans changement visible ? Voir le
     * Javadoc de classe pour pourquoi c'est une correspondance EXACTE, jamais approximative.
     *
     * <h2>Risque latent traité ici : la géométrie, pas seulement l'apparence</h2>
     *
     * <p>Avant cette vérification, {@code mergeable()} ne comparait que sprite/couleur/lumière — trois
     * propriétés purement visuelles. Deux blocs DIFFÉRENTS (mod tiers, ou même vanilla — une dalle et
     * un bloc plein partagent parfois une texture identique) pourraient par coïncidence avoir le même
     * sprite/couleur/lumière tout en ayant une face UP de forme différente (hauteur, empan). Sans
     * vérifier la géométrie, {@link #flush} aurait pu fusionner leurs quads : {@code putMergedRunAlongX}
     * n'utilise QUE les positions de {@code first} et {@code last} du run pour émettre le quad fusionné
     * — les quads intermédiaires du run sont ignorés en position (seuls sprite/couleur/lumière sont
     * vérifiés pas à pas), donc une forme différente au milieu d'un run disparaîtrait silencieusement du
     * rendu, et même {@code first}/{@code last} pourraient différer entre eux sans qu'aucun garde-fou
     * existant ne le remarque.
     *
     * <p>Corrigé en comparant directement les 4 coins locaux du quad ({@link BakedQuad#position(int)}) —
     * la géométrie de face réellement dessinée — plutôt que de supposer que même texture implique même
     * forme. {@code position(i)} est déjà relatif au bloc (voir {@link TerrainVertexFormat#putQuad}, qui
     * fait {@code x + pos.x()}), donc deux faces UP pleines et identiques ont des positions locales
     * BIT-IDENTIQUES quel que soit le bloc auquel elles appartiennent ou sa position dans le monde — une
     * simple égalité à epsilon suffit, aucune notion d'axe de fusion à soustraire. Comparer indice par
     * indice (pas seulement min/max) couvre aussi les quads non rectangulaires (faces pivotées) qu'une
     * comparaison de boîte englobante seule laisserait passer.
     */
    private static boolean mergeable(Pending a, Pending b) {
        float[] boundsA = TerrainVertexFormat.spriteBounds(a.quad);
        float[] boundsB = TerrainVertexFormat.spriteBounds(b.quad);
        float epsilon = 1.0e-5f;
        for (int k = 0; k < 4; k++) {
            if (Math.abs(boundsA[k] - boundsB[k]) > epsilon) {
                return false;
            }
        }
        for (int i = 0; i < 4; i++) {
            if (a.colors[i] != b.colors[i]) {
                return false;
            }
            if (a.lights[i] != b.lights[i]) {
                return false;
            }
        }
        float geomEpsilon = 1.0e-4f;
        for (int i = 0; i < 4; i++) {
            Vector3fc pa = a.quad.position(i);
            Vector3fc pb = b.quad.position(i);
            if (Math.abs(pa.x() - pb.x()) > geomEpsilon
                    || Math.abs(pa.y() - pb.y()) > geomEpsilon
                    || Math.abs(pa.z() - pb.z()) > geomEpsilon) {
                return false;
            }
        }
        return true;
    }

    /** Un résumé lisible du gain de la dernière section traitée sur ce thread — voir Radiographie. */
    public static String report() {
        long in = TOTAL_IN.get();
        long out = TOTAL_OUT.get();
        if (in == 0) {
            return "greedy : aucun quad UP+DOWN/SOLID vu";
        }
        double ratio = out / (double) in;
        return String.format(java.util.Locale.ROOT,
                "greedy : %d quad(s) UP+DOWN/SOLID -> %d quad(s) emis (x%.3f), cumule depuis le demarrage",
                in, out, ratio);
    }

    static {
        if (ENABLED) {
            Lanterne.LOG.info("[GREEDY] fusion de faces armee (UP+DOWN/SOLID, axe X) — "
                    + "LANTERNE_GREEDY_MESH=0 pour comparer sans elle.");
        }
    }
}
