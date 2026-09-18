package fr.clubcitrouille.lanterne.lab;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Capture d'écran automatique pour vérifier visuellement un changement de rendu sans humain devant
 * l'écran — l'outil qui a manqué à tous les forks précédents sur le maillage de terrain et FSR
 * temporel : un plan correct par lecture de bytecode ne suffit pas à savoir si le résultat a des
 * trous, des textures décalées, ou du ghosting. Il faut le VOIR.
 *
 * <p>Gated par la variable d'environnement {@code LANTERNE_AUTO_SCREENSHOT=1} — jamais actif pour un
 * joueur normal. Détecte l'apparition d'un monde exactement comme {@link Radiographie#pulse()}
 * ({@code mc.level != null}, transition depuis {@code null}), puis prend trois captures à 3, 6 et 9
 * secondes — le temps que les chunks proches se chargent et se maillent avant le premier cliché, puis
 * deux de plus pour couvrir un chargement encore en cours.
 *
 * <p>Écrit dans le dossier {@code screenshots/} standard du jeu ({@link Screenshot#SCREENSHOT_DIR}),
 * relatif au dossier de lancement (l'instance CurseForge ou {@code run/} en dev) — chemin annoncé en
 * log à chaque capture pour qu'un agent puisse le lire directement avec son propre outil de lecture
 * d'image, sans deviner.
 */
public final class Snap {
    private static final boolean ARMED = "1".equals(System.getenv("LANTERNE_AUTO_SCREENSHOT"));
    private static final long[] DELAYS_NANOS = {3_000_000_000L, 6_000_000_000L, 9_000_000_000L};

    private static boolean wasInWorld;
    private static long worldSeenAtNanos;
    private static int nextDelayIndex;

    private Snap() {}

    public static void pulse() {
        if (!ARMED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.level != null;

        if (inWorld && !wasInWorld) {
            worldSeenAtNanos = System.nanoTime();
            nextDelayIndex = 0;
            // Ce process n'a jamais le focus OS (lancement de fond pour la verification) : sans
            // ceci, le jeu se met en pause automatiquement et floute le rendu derriere le menu
            // pause des la premiere image - constate en pratique sur les premieres captures, qui
            // ne montraient qu'un "Game Menu" flou au lieu du monde.
            mc.options.pauseOnLostFocus = false;
            Lanterne.LOG.info("[SNAP] monde apparu, capture(s) programmee(s)");
        }
        wasInWorld = inWorld;

        if (!inWorld || nextDelayIndex >= DELAYS_NANOS.length) {
            return;
        }
        long elapsed = System.nanoTime() - worldSeenAtNanos;
        if (elapsed < DELAYS_NANOS[nextDelayIndex]) {
            return;
        }
        int index = nextDelayIndex;
        nextDelayIndex++;
        // grab(Minecraft, boolean) : verifie par bytecode (javap -c), pas suppose - le booleen
        // ne veut PAS dire "silencieux". A true, et seulement si SharedConstants.DEBUG_PANORAMA
        // _SCREENSHOT est aussi actif, il declenche une sequence de capture panoramique pour les
        // fonds de menu - un chemin totalement different. false prend le chemin normal
        // (grab(gameDirectory, mainRenderTarget, consumer)), exactement la capture simple voulue.
        Screenshot.grab(mc, false);
        Lanterne.LOG.info("[SNAP] capture {} ecrite dans {}/ (racine de lancement)",
                index + 1, Screenshot.SCREENSHOT_DIR);
    }
}
