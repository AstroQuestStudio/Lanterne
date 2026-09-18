package fr.clubcitrouille.lanterne.client.screen;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.client.upscale.Nuancier;
import fr.clubcitrouille.lanterne.client.upscale.Upscale;

/**
 * Vérification automatique de {@link Palette}, sans humain devant l'écran — même besoin, même
 * geste que {@code lab.Snap} pour les captures et que le {@code [NUANCIER-TEST]} déjà écrit pour la
 * commande : un plan correct par lecture de code ne suffit pas à savoir si l'écran s'ouvre
 * réellement, montre la vraie liste, et si un clic change vraiment le nuancier actif.
 *
 * <h2>Gated, comme {@code lab.Snap}</h2>
 *
 * <p>{@code LANTERNE_PALETTE_TEST=1} uniquement — jamais actif pour un joueur normal. Sans variable,
 * cette classe ne fait rien du tout : elle est chargée (côté client seulement, voir
 * {@code @EventBusSubscriber(..., Dist.CLIENT)}) mais son écouteur sort immédiatement.
 *
 * <h2>Pourquoi un fichier à part plutôt qu'un ajout à {@code report.SelfTest}</h2>
 *
 * <p>{@code SelfTest} est un fichier d'un autre chantier en cours cette nuit sur ce dépôt — voir son
 * historique récent. Y ajouter une vérification d'écran aurait demandé de lire et de comprendre un
 * protocole serveur déjà en mouvement pour un besoin qui n'a rien à voir : celui-ci ne regarde que
 * le client, et {@code Nuancier}/{@code Upscale} n'ont besoin d'aucun monde partagé pour être vérifiés.
 *
 * <h2>La séquence</h2>
 *
 * <ol>
 *   <li><b>t+2s</b> après l'apparition du monde : ouvre {@code client.Dials} sur la famille
 *       « L'image » (index 2) — la porte réelle, celle que {@code mixin.VideoOptionsMixin} pose dans
 *       Options → Graphismes.</li>
 *   <li><b>t+4s</b> : première capture — la ligne « Nuanciers » doit s'y lire, à côté de
 *       « Upscaling ».</li>
 *   <li><b>t+5s</b> : ouvre {@link Palette}, journalise la vraie liste lue par
 *       {@link Nuancier#listNames()}.</li>
 *   <li><b>t+7s</b> : deuxième capture — l'écran, tel qu'un joueur le verrait.</li>
 *   <li><b>t+8s</b> : {@link Palette#testClick(String)} sur le premier nuancier trouvé, et
 *       journalise {@link Upscale#nuancierActif()} avant/après — la preuve que le clic a vraiment
 *       changé l'état, pas seulement que l'écran s'est dessiné.</li>
 *   <li><b>t+10s</b> : troisième capture — le bouton du nuancier choisi doit s'y lire « (actif) ».</li>
 * </ol>
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class PaletteSelfCheck {
    private static final boolean ARMED = "1".equals(System.getenv("LANTERNE_PALETTE_TEST"));

    private static boolean wasInWorld;
    private static long worldSeenAtNanos;
    private static int step;

    private PaletteSelfCheck() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ARMED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.level != null;

        if (inWorld && !wasInWorld) {
            worldSeenAtNanos = System.nanoTime();
            step = 0;
            // Même raison que lab.Snap : ce process n'a jamais le focus OS pendant une vérification
            // automatisée, et sans ceci le jeu se met en pause et floute tout derrière le menu pause.
            mc.options.pauseOnLostFocus = false;
            Lanterne.LOG.info("[PALETTE-TEST] monde apparu, séquence programmée");
        }
        wasInWorld = inWorld;
        if (!inWorld) {
            return;
        }

        long elapsedSec = (System.nanoTime() - worldSeenAtNanos) / 1_000_000_000L;
        switch (step) {
            // La porte réelle d'abord : Dials, famille « L'image » (index 2), là où la ligne
            // « Nuanciers » ajoutée à Dials.buildImage() doit apparaître — la même famille que
            // rejoint un joueur qui ouvre Options → Graphismes → 🎃 Lanterne — rendu.
            case 0 -> {
                if (elapsedSec >= 2) {
                    Lanterne.LOG.info("[PALETTE-TEST] ouverture de Dials, famille « L'image »");
                    mc.gui.setScreen(new fr.clubcitrouille.lanterne.client.Dials(null, 2));
                    step = 1;
                }
            }
            case 1 -> {
                if (elapsedSec >= 4) {
                    Screenshot.grab(mc, false);
                    Lanterne.LOG.info("[PALETTE-TEST] capture 1 (Dials, ligne Nuanciers) écrite dans"
                            + " {}/", Screenshot.SCREENSHOT_DIR);
                    step = 2;
                }
            }
            case 2 -> {
                if (elapsedSec >= 5) {
                    List<String> noms = Nuancier.listNames();
                    Lanterne.LOG.info("[PALETTE-TEST] ouverture de Palette — Nuancier.listNames() = {}",
                            noms);
                    mc.gui.setScreen(new Palette(null));
                    step = 3;
                }
            }
            case 3 -> {
                if (elapsedSec >= 7) {
                    Screenshot.grab(mc, false);
                    Lanterne.LOG.info("[PALETTE-TEST] capture 2 (liste, avant clic) écrite dans {}/",
                            Screenshot.SCREENSHOT_DIR);
                    step = 4;
                }
            }
            case 4 -> {
                if (elapsedSec >= 8) {
                    List<String> noms = Nuancier.listNames();
                    if (noms.isEmpty()) {
                        Lanterne.LOG.warn("[PALETTE-TEST] aucun nuancier trouvé — rien à cliquer, "
                                + "voir exemple_teinte/ et club_citrouille/ sous shaderpacks/");
                    } else if (mc.gui.screen() instanceof Palette palette) {
                        String cible = noms.get(0);
                        String avant = Upscale.nuancierActif();
                        palette.testClick(cible);
                        Lanterne.LOG.info(
                                "[PALETTE-TEST] clic simulé sur '{}' — nuancierActif() : '{}' -> '{}'"
                                        + " (attendu : '{}')",
                                cible, avant, Upscale.nuancierActif(), cible);
                    } else {
                        Lanterne.LOG.warn("[PALETTE-TEST] l'écran actif n'est plus Palette — clic "
                                + "impossible");
                    }
                    step = 5;
                }
            }
            case 5 -> {
                if (elapsedSec >= 10) {
                    Screenshot.grab(mc, false);
                    Lanterne.LOG.info(
                            "[PALETTE-TEST] capture 3 (après clic) écrite — nuancierActif() final = '{}'",
                            Upscale.nuancierActif());
                    step = 6;
                }
            }
            default -> {
                // Terminé : on laisse l'écran ouvert, pour qu'une capture manuelle supplémentaire
                // reste possible sans avoir à rejouer toute la séquence.
            }
        }
    }
}
