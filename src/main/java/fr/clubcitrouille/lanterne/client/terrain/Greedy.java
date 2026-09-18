package fr.clubcitrouille.lanterne.client.terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <h2>Le périmètre choisi pour ce premier jalon</h2>
 *
 * <p>Uniquement les faces {@link Direction#UP} de la couche {@link ChunkSectionLayer#SOLID}, fusionnées
 * le long de l'axe X local, à l'intérieur d'une même rangée (Y, Z fixes). C'est délibérément le cas le
 * plus simple — un sol ou un plafond plat d'un seul type de bloc — avant d'envisager les faces
 * latérales, l'axe Z, ou un vrai balayage 2D. La consigne du chantier est explicite là-dessus :
 * vérifier un cas simple avant les cas complexes, pas les livrer tous à l'aveugle.
 *
 * <h2>Comment ça s'articule avec {@code SectionCompilerMixin}</h2>
 *
 * <p>{@code SectionCompilerMixin} appelle {@link #accept} à la place d'émettre directement. Un quad
 * UP/SOLID est mis de côté (pas encore écrit dans le tampon) ; tout le reste (faces latérales, DOWN,
 * CUTOUT, TRANSLUCENT) part immédiatement par {@link TerrainVertexFormat#putQuad} comme avant cette
 * passe — le périmètre non couvert ici n'est donc pas dégradé, juste pas encore fusionné.
 *
 * <p>{@link #flush} est appelé une fois par section, juste avant que son tampon SOLID ne soit figé
 * ({@code BufferBuilder.build()}) — voir le point d'injection exact dans {@code SectionCompilerMixin}.
 * Il regroupe les quads mis de côté par rangée (Y, Z), les trie par X, fusionne les runs consécutifs
 * qui partagent la même clé ({@link #mergeable}), et émet soit un quad unique (run de longueur 1) soit
 * un quad fusionné ({@link TerrainVertexFormat#putMergedRunAlongX}) — avec repli individuel si cette
 * dernière refuse la géométrie.
 *
 * <h2>Sécurité de fusion : à l'identique, jamais à l'approximatif</h2>
 *
 * <p>{@link #mergeable} exige une correspondance EXACTE des bornes de sprite (même texture) et de la
 * couleur finale ET de la lumière des 4 sommets entre les deux quads comparés. Un seul sommet qui
 * diffère (occlusion ambiante différente au bord d'un bloc voisin, teinte de biome légèrement
 * différente) bloque la fusion pour cette paire — un repli sûr : la pire conséquence d'un refus de
 * fusion est un gain plus faible que possible, jamais un artefact visuel.
 */
public final class Greedy {
    /**
     * {@code LANTERNE_GREEDY_MESH=1} active la fusion — DÉSACTIVÉE PAR DÉFAUT tant qu'elle n'est pas
     * vérifiée saine.
     *
     * <h2>Pourquoi ce n'est plus activé par défaut</h2>
     *
     * <p>Le premier vrai test client a montré un sol de neige fusionné rendu en larges carrés GRIS
     * (pas blancs), avec des lignes de bord visibles entre eux — vu directement par l'utilisateur sur
     * son écran pendant cette passe. Une première cause a été identifiée et corrigée (discontinuité de
     * dérivée d'écran au bord de tuile, voir {@code terrain.fsh}), mais un second test après ce
     * correctif montre le défaut TOUJOURS présent — la géométrie ou l'UV fusionnés visent donc encore
     * la mauvaise région de l'atlas, pas seulement le mauvais niveau de mip. Cause exacte non encore
     * confirmée. Le format étendu (UV1/UV3, shader) reste lui vérifié sain et actif inconditionnellement
     * — seule LA FUSION elle-même, {@link #accept}/{@link #flush}, est neutralisée ici.
     */
    private static final boolean ENABLED = "1".equals(System.getenv("LANTERNE_GREEDY_MESH"));

    /** Un quad mis de côté, en attendant de savoir avec quoi le fusionner. */
    private static final class Pending {
        final float x;
        final float y;
        final float z;
        final BakedQuad quad;
        final QuadInstance instance;

        Pending(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.quad = quad;
            this.instance = instance;
        }
    }

    /** État accumulé pour UNE section en cours de compilation sur CE thread. */
    private static final class State {
        BufferBuilder solidBuffer;
        final List<Pending> upQuads = new ArrayList<>();
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
        if (quad.direction() != Direction.UP) {
            return false;
        }
        if (quad.materialInfo().layer() != ChunkSectionLayer.SOLID) {
            return false;
        }
        State state = STATE.get();
        state.solidBuffer = buffer;
        state.upQuads.add(new Pending(x, y, z, quad, instance));
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
        if (state.upQuads.isEmpty()) {
            return;
        }
        BufferBuilder buffer = state.solidBuffer;

        // Regroupe par rangée (Y, Z fixes) — la fusion de ce jalon ne balaie que X à l'intérieur d'une
        // rangée. Clé entière : x/y/z passés à putBlockBakedQuad sont les coordonnées locales d'un
        // bloc dans la section, toujours entières en valeur (même si portées en float).
        Map<Long, List<Pending>> rows = new HashMap<>();
        for (Pending p : state.upQuads) {
            long key = (Math.round(p.y) & 0xFFFFL) << 16 | (Math.round(p.z) & 0xFFFFL);
            rows.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        long in = state.upQuads.size();
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
                            first.instance);
                    out++;
                } else {
                    Pending last = row.get(j - 1);
                    boolean merged = TerrainVertexFormat.putMergedRunAlongX(buffer,
                            first.x, first.y, first.z, first.quad, first.instance,
                            last.x, last.y, last.z, last.quad, last.instance, runLength);
                    if (merged) {
                        out++;
                    } else {
                        // Repli sûr : la géométrie ne correspondait pas aux hypothèses vérifiées de
                        // putMergedRunAlongX (voir son Javadoc) — émet chaque quad du run séparément
                        // plutôt que de risquer une géométrie fausse.
                        for (int k = i; k < j; k++) {
                            Pending p = row.get(k);
                            TerrainVertexFormat.putQuad(buffer, p.x, p.y, p.z, p.quad, p.instance);
                            out++;
                        }
                    }
                }
                i = j;
            }
        }

        state.upQuads.clear();
        state.solidBuffer = null;
        state.quadsIn += in;
        state.quadsOut += out;
        TOTAL_IN.addAndGet(in);
        TOTAL_OUT.addAndGet(out);
    }

    /**
     * Deux quads consécutifs le long de X peuvent-ils fusionner sans changement visible ? Voir le
     * Javadoc de classe pour pourquoi c'est une correspondance EXACTE, jamais approximative.
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
        int lightEmissionA = a.quad.materialInfo().lightEmission();
        int lightEmissionB = b.quad.materialInfo().lightEmission();
        for (int i = 0; i < 4; i++) {
            int colorA = ARGB.multiply(a.instance.getColor(i), a.quad.bakedColors().color(i));
            int colorB = ARGB.multiply(b.instance.getColor(i), b.quad.bakedColors().color(i));
            if (colorA != colorB) {
                return false;
            }
            int lightA = a.instance.getLightCoordsWithEmission(i, lightEmissionA);
            int lightB = b.instance.getLightCoordsWithEmission(i, lightEmissionB);
            if (lightA != lightB) {
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
            return "greedy : aucun quad UP/SOLID vu";
        }
        double ratio = out / (double) in;
        return String.format(java.util.Locale.ROOT,
                "greedy : %d quad(s) UP/SOLID -> %d quad(s) emis (x%.3f), cumule depuis le demarrage",
                in, out, ratio);
    }

    static {
        if (ENABLED) {
            Lanterne.LOG.info("[GREEDY] fusion de faces armee (UP/SOLID, axe X) — "
                    + "LANTERNE_GREEDY_MESH=0 pour comparer sans elle.");
        }
    }
}
