package fr.clubcitrouille.lanterne.lab;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le vertige : fait pivoter la caméra vite et tout seul, pour que {@link Snap} ait vraiment quelque
 * chose en mouvement à photographier.
 *
 * <h2>Pourquoi ce fichier existe</h2>
 *
 * <p>Le ghosting d'un remonteur temporel ne se voit qu'en bougeant la caméra vite — voir
 * {@code notes/fsr2-plan.md}. {@link Snap} sait capturer trois images à 3/6/9 secondes après
 * l'apparition du monde, mais un client lancé sans main humaine derrière n'a, par défaut, AUCUN
 * mouvement : le joueur reste exactement où la sauvegarde l'a laissé, immobile. Une caméra immobile
 * est justement le cas que {@link fr.clubcitrouille.lanterne.client.upscale.Jitter} documente comme
 * sans intérêt pour un remonteur temporel — zéro nouvelle information par image, donc zéro ghosting
 * observable, quel que soit l'état réel du mélange historique/scène.
 *
 * <p>Une injection d'entrée au niveau du système d'exploitation (souris/clavier synthétiques) a été
 * essayée avant cette classe et a échoué en pratique : le processus qui pilote ce client tourne
 * derrière un agent d'arrière-plan, sans le focus du bureau, et Windows refuse de céder le focus à
 * une fenêtre dont le processus appelant n'est pas déjà lui-même au premier plan — confirmé en
 * comparant des captures avant/après l'injection, rigoureusement identiques. Le clavier et la souris
 * synthétiques n'atteignaient donc jamais le jeu.
 *
 * <h2>Comment elle tourne la caméra sans passer par les périphériques</h2>
 *
 * <p>Même recette que {@link Glass}, appliquée pour la même raison : {@code snapTo(x, y, z, yRot,
 * xRot)} à CHAQUE image de rendu, jamais au tick. Cette méthode fixe aussi la position et la
 * rotation <em>précédentes</em> ({@code setOldPosAndRot()}) au même point, donc aucune interpolation
 * ne lisse le mouvement entre deux images : la caméra est exactement où on le dit, image après
 * image — un panoramique net et reproductible plutôt qu'un mélange flou d'entrées jamais arrivées.
 *
 * <p>Gated par {@code LANTERNE_VERTIGE=1} — jamais actif pour un joueur normal, exactement comme
 * {@link Snap}. Outil de diagnostic, pas un réglage de jeu.
 */
public final class Vertige {
    private static final boolean ARMED = "1".equals(System.getenv("LANTERNE_VERTIGE"));

    /**
     * Vitesse de rotation, en degrés par seconde.
     *
     * <p>Choisie pour un panoramique rapide mais plausible — un joueur qui se retourne vite pour
     * vérifier un flanc, pas une toupie. Un premier essai à 220 deg/s (balayer tout l'écran en une
     * seconde) a servi à prouver que le ghosting existe ; celle-ci sert à juger si le défaut se voit
     * encore dans les conditions d'un joueur réel plutôt que dans un cas volontairement extrême.
     */
    private static final float DEGREES_PER_SECOND = 90f;

    /** Vitesse d'avance, en blocs par seconde — un peu de translation en plus du panoramique pur. */
    private static final float BLOCKS_PER_SECOND = 4f;

    /**
     * Plafond du pas d'une image, en secondes.
     *
     * <p>Une image anormalement longue (compilation de shader, tout premier rendu du monde) ne doit
     * pas faire faire un bond de caméra énorme d'un coup : même réserve que {@link Glass} au sujet
     * d'un gel du ramasse-miettes.
     */
    private static final double MAX_STEP_SECONDS = 0.25d;

    private static long lastNanos;
    private static float yaw;
    private static boolean initialized;

    private Vertige() {}

    /** Cet outil est-il armé pour cette session ? Pour que l'appelant sache s'il doit se soucier de lui. */
    public static boolean armed() {
        return ARMED;
    }

    /** Avance la caméra d'une image. Appelé depuis {@code Pane.onFrame}, comme {@link Snap#pulse()}. */
    public static void pulse() {
        if (!ARMED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) {
            // Pas de monde, pas de joueur : rien à tourner, et la prochaine apparition doit repartir
            // du cap réel du joueur à cet instant-là, pas d'un cap mémorisé d'une session précédente.
            initialized = false;
            return;
        }

        long now = System.nanoTime();
        if (!initialized) {
            yaw = player.getYRot();
            lastNanos = now;
            initialized = true;
            return;
        }

        double dtSeconds = Math.min(MAX_STEP_SECONDS, (now - lastNanos) / 1e9d);
        lastNanos = now;

        yaw += DEGREES_PER_SECOND * dtSeconds;
        float pitch = player.getXRot();

        // Meme convention que le calcul de vecteur de direction de vanilla (Entity.getLookAngle) :
        // yRot = 0 regarde vers le sud (+Z).
        double radians = Math.toRadians(yaw);
        double dx = -Math.sin(radians) * BLOCKS_PER_SECOND * dtSeconds;
        double dz = Math.cos(radians) * BLOCKS_PER_SECOND * dtSeconds;

        player.snapTo(player.getX() + dx, player.getY(), player.getZ() + dz, yaw, pitch);
    }
}
