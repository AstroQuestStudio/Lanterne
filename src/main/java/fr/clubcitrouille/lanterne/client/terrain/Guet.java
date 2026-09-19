package fr.clubcitrouille.lanterne.client.terrain;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

import fr.clubcitrouille.lanterne.core.ClientConfig;

/**
 * Le guet : observe, sans jamais y toucher, si le regroupement des sections de chunk visibles
 * change d'une image à l'autre — pour savoir si le mettre en cache vaudrait la peine, avant
 * d'essayer.
 *
 * <h2>Ce qui a motivé ce module, vérifié par bytecode, pas supposé</h2>
 *
 * <p>La mission de départ demandait de remplacer un appel de dessin par section de chunk par un
 * seul appel indirect multi-lots ({@code vkCmdDrawIndexedIndirect}). Avant d'écrire une ligne de
 * mixin, lecture du vrai jar patché ({@code javap -p -c -constants}, jamais {@code .mcsrc/}) sur
 * {@code LevelRenderer}, {@code ChunkSectionsToRender} et {@code VulkanRenderPass} : <b>ce
 * mécanisme existe déjà entièrement dans le moteur, et il est actif</b> — pas seulement
 * disponible. {@code LevelRenderer.prepareChunkRendersIndirect} construit un
 * {@code ChunkSectionsToRender.DrawIndirect} par regroupement de sections partageant un même
 * tampon GPU ({@code LevelRenderer.ChunkDrawGroup}, bâti par
 * {@code LevelRenderer.extractSectionDrawGroups}), et
 * {@code ChunkSectionsToRender.DrawIndirect.render} appelle
 * {@code RenderPass.drawIndexedIndirect(GpuBufferSlice, int)} — dont l'implémentation Vulkan
 * réelle, {@code VulkanRenderPass.drawIndexedIndirect}, appelle littéralement
 * {@code org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirect}, en respectant le
 * {@code maxDrawIndirectDrawCount} du pilote. Confirmé actif, pas seulement câblé : la session en
 * jeu réelle citée par la mission ({@code config/lanterne/profil-2026-09-19_21-04-36.txt},
 * retrouvée dans une AUTRE instance que ce dépôt, {@code curseforge/.../Instances/test 1/}) montre
 * {@code prepareChunkRendersIndirect} dans son arbre d'appel — pas
 * {@code prepareChunkRenders}, le chemin de repli à un dessin par section. Ce n'est pas du code
 * Lanterne : {@code LevelRenderer.java} et {@code ChunkSectionsToRender.java} sont des fichiers
 * vanilla (voir leur en-tête {@code Compiled from} dans le désassemblage).
 *
 * <p>Le coût de 6,3 % mesuré dans {@code extractSectionDrawGroups} sur cette même session n'est
 * donc PAS le coût de N appels de dessin séparés — ce coût-là est déjà éliminé. C'est le coût CPU
 * du <b>regroupement</b> lui-même : parcourir chaque section visible, pour chaque couche de
 * rendu, calculer une clé de tampon partagé, et construire la liste de dessins indirects — un
 * travail refait intégralement à chaque image, y compris quand la caméra ne bouge pas et
 * qu'aucune section ne recompile.
 *
 * <h2>Pourquoi ce module n'essaie pas de mettre en cache ce regroupement</h2>
 *
 * <p>La piste existe : ne reconstruire {@code ChunkDrawGroup} que lorsque la liste de sections
 * visibles (ou le tampon compilé d'une section qui la compose) change réellement. Mais
 * {@code extractSectionDrawGroups} mélange aujourd'hui, dans la même boucle, une part
 * structurellement STATIQUE (quelles sections partagent quel tampon GPU) et une part ANIMÉE à
 * chaque image ({@code DynamicGpuData.ChunkSectionInfo.visibility}, le fondu d'apparition d'une
 * section, ET l'index qui relie un dessin à sa position dans ce tableau reconstruit chaque
 * image). Séparer proprement les deux est une vraie refonte du chemin de rendu de terrain — le
 * système le plus central et, ce soir, le plus démontré fragile de ce moteur (trois bogues réels
 * en cascade sur d'autres chantiers Vulkan, découverts uniquement en jeu). Ce dépôt n'a pas de
 * client sous la main pour vérifier visuellement une refonte pareille dans cette passe — donc elle
 * n'a pas été tentée à l'aveugle.
 *
 * <p>Ce que ce module fait à la place : mesurer honnêtement si cette refonte vaudrait le risque,
 * pour que la prochaine passe qui peut vraiment lancer un client n'ait pas à deviner.
 *
 * <h2>Comment il mesure, et pourquoi c'est sans danger pour le rendu</h2>
 *
 * <p>{@link #pulse} ne fait que lire {@link LevelRenderer#visibleSections()} — déjà une méthode
 * publique du moteur, aucun mixin, aucune injection de bytecode dans le chemin de rendu. Il en
 * tire une signature légère (taille de la liste, identité de chaque
 * {@code SectionRenderDispatcher.RenderSection}, ET identité de son {@link SectionMesh} compilé
 * courant — un recompilage change ce dernier même si la section elle-même reste le même objet, ce
 * qui invaliderait exactement un futur cache du regroupement) et la compare à celle de l'image
 * précédente. Rien n'est jamais écrit dans le moteur : ce module ne peut donc, par construction,
 * rien casser dans le rendu de terrain, contrairement à la refonte qu'il évalue.
 *
 * <h2>Non mesuré, donc éteint par défaut</h2>
 *
 * <p>Conformément à la règle de ce dépôt, un module non mesuré n'existe pas — voir
 * {@code fr.clubcitrouille.lanterne.core.Horizon} pour le précédent. Celui-ci n'a jamais tourné en
 * jeu : {@link ClientConfig#GUET} reste éteint tant qu'une vraie session
 * ({@code lab/Glass.java}, {@code LANTERNE_GLASS=1}, scène avec beaucoup de chunks visibles) n'a
 * pas confirmé que le coût de lecture ({@code O(sections visibles)} par image, bien moindre que
 * {@code extractSectionDrawGroups} lui-même qui boucle en plus sur les couches) reste négligeable.
 */
public final class Guet {
    private Guet() {}

    private static final AtomicLong FRAMES_OBSERVED = new AtomicLong();
    private static final AtomicLong FRAMES_UNCHANGED = new AtomicLong();
    private static final AtomicLong CURRENT_RUN = new AtomicLong();
    private static final AtomicLong LONGEST_RUN = new AtomicLong();

    // Lus/écrits uniquement depuis le fil de rendu (comme LevelRenderer lui-même) : pas besoin
    // d'atomique pour ces deux-là, contrairement aux compteurs ci-dessus que Radiographie peut lire
    // depuis un autre fil au moment d'écrire son rapport.
    private static long lastSignature;
    private static boolean hasLastSignature;

    /**
     * Appelée depuis {@code Pane.onFrame}, chaque image — sans rien faire si
     * {@link ClientConfig#GUET} est éteint (défaut). Ne lit que de l'API publique de
     * {@link LevelRenderer}, n'y écrit jamais.
     */
    public static void pulse() {
        if (!ClientConfig.GUET.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LevelRenderer renderer = mc.levelRenderer;
        if (renderer == null) {
            return;
        }
        ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = renderer.visibleSections();
        if (visible == null) {
            return;
        }
        long signature = signatureOf(visible);
        FRAMES_OBSERVED.incrementAndGet();
        if (hasLastSignature) {
            if (signature == lastSignature) {
                FRAMES_UNCHANGED.incrementAndGet();
                long run = CURRENT_RUN.incrementAndGet();
                LONGEST_RUN.updateAndGet(previous -> Math.max(previous, run));
            } else {
                CURRENT_RUN.set(0);
            }
        }
        lastSignature = signature;
        hasLastSignature = true;
    }

    /**
     * Une signature légère : la taille de la liste, l'identité de chaque section visible, ET
     * l'identité de son maillage compilé courant. Voir la note de classe sur pourquoi les deux
     * composantes comptent : un futur cache du regroupement de {@code extractSectionDrawGroups}
     * devrait s'invalider sur l'une comme sur l'autre.
     */
    private static long signatureOf(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
        long h = 1125899906842597L;
        h = h * 31 + visible.size();
        for (SectionRenderDispatcher.RenderSection section : visible) {
            h = h * 31 + System.identityHashCode(section);
            SectionMesh mesh = section.sectionMesh.get();
            h = h * 31 + (mesh == null ? 0 : System.identityHashCode(mesh));
        }
        return h;
    }

    /** Un résumé lisible pour Radiographie. */
    public static String report() {
        long observed = FRAMES_OBSERVED.get();
        if (observed < 2) {
            return "guet : desactive (reglage \"guet\" eteint, ou session trop courte)";
        }
        long comparisons = observed - 1;
        long unchanged = FRAMES_UNCHANGED.get();
        double pct = comparisons > 0 ? 100.0 * unchanged / comparisons : 0;
        return String.format(Locale.ROOT,
                "guet : sections visibles identiques a l'image precedente sur %d/%d images (%.1f%%), "
                        + "plus longue serie stable : %d image(s) consecutive(s)",
                unchanged, comparisons, pct, LONGEST_RUN.get());
    }
}
