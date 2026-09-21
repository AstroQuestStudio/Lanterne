package fr.clubcitrouille.lanterne.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL43C;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Banc d'essai : coupe {@code GL_DEBUG_OUTPUT_SYNCHRONOUS} après la création du contexte OpenGL.
 *
 * <p>Sodium l'active pour tout GPU NVIDIA sous Windows ({@code NvidiaWorkarounds.applyContextChanges},
 * journal « Enabling GL_DEBUG_OUTPUT_SYNCHRONOUS to force the NVIDIA driver to disable threaded
 * command submission »). Effet documenté par Sodium : le pilote n'envoie plus ses commandes GL depuis
 * un fil dédié, tout le travail du pilote retombe sur le fil de rendu. Cette classe ne fait RIEN par
 * défaut : elle n'agit que si la propriété {@code lanterne.glsync=off} est posée, pour mesurer avant
 * de décider quoi que ce soit.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class GlSync {
    private static boolean done;

    private GlSync() {}

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (done) {
            return;
        }
        done = true;
        String mode = System.getProperty("lanterne.glsync", "");
        if (!"off".equals(mode) && !"off-all".equals(mode)) {
            return;
        }
        try {
            boolean before = GL11C.glIsEnabled(GL43C.GL_DEBUG_OUTPUT_SYNCHRONOUS);
            GL11C.glDisable(GL43C.GL_DEBUG_OUTPUT_SYNCHRONOUS);
            if ("off-all".equals(mode)) {
                GL11C.glDisable(GL43C.GL_DEBUG_OUTPUT);
            }
            Lanterne.LOG.info("[GLSYNC] GL_DEBUG_OUTPUT_SYNCHRONOUS était {}, coupé ({}).",
                    before ? "actif" : "inactif", mode);
        } catch (Throwable failure) {
            Lanterne.LOG.warn("[GLSYNC] impossible : {}", failure.toString());
        }
    }
}
