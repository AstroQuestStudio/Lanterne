package fr.clubcitrouille.lanterne.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.core.ClientConfig;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Les faces que deux feuilles collées se cachent l'une l'autre.
 *
 * <h2>Le gain, et la raison pour laquelle vanilla ne le prend pas</h2>
 *
 * <p>Un chêne est une boule de feuilles. Chacune expose six faces, et la plupart de ces faces sont
 * plaquées contre une autre feuille — donc maillées, envoyées à la carte graphique, et dessinées
 * pour rien. Vanilla ne les supprime pas parce que l'option vidéo <b>Feuilles ajourées</b>
 * ({@code cutoutLeaves}) peut les rendre percées : on voit alors à travers, et la face du dessous
 * est parfois réellement visible.
 *
 * <p>Cette option coupée, les feuilles sont pleines. La face cachée l'est pour de bon, et la
 * supprimer est exact, pas approché.
 *
 * <h2>Pourquoi le réglage a trois états</h2>
 *
 * <p>Le mode {@code AUTO} lit l'option que le joueur a déjà posée et n'agit que si les feuilles sont
 * pleines. C'est le défaut, parce qu'il ne peut produire aucun artefact : il ne supprime que des
 * faces dont le jeu garantit lui-même qu'elles sont invisibles.
 *
 * <p>{@code TOUJOURS} existe parce que beaucoup de joueurs gardent les feuilles ajourées pour
 * l'aspect et se moquent de voir l'intérieur d'un arbre. Le gain est alors maximal, et le défaut — des trous
 * dans le feuillage quand on colle son visage contre un tronc — est annoncé dans le fichier de
 * réglages plutôt que découvert en jeu.
 *
 * <h2>Pourquoi l'état est recopié dans un champ</h2>
 *
 * <p>{@code skipRendering} est appelé pendant le maillage d'un chunk, qui s'exécute sur des fils de
 * travail, des centaines de milliers de fois par reconstruction. Y consulter deux objets d'option et
 * une instance de {@code Minecraft} à chaque appel serait à la fois lent et douteux du point de vue
 * des fils. Un booléen recopié une fois par image répond à la même question sans aucun de ces deux
 * défauts.
 */
public final class Foliage {
    /**
     * Faut-il masquer les faces mitoyennes en ce moment ?
     *
     * <p>Volatile parce qu'écrit sur le fil de rendu et lu sur les fils de maillage. C'est la
     * lecture la plus chaude du module : elle doit rester un accès à un champ, et rien d'autre.
     */
    private static volatile boolean masking;

    private static ClientConfig.LeafCulling lastMode = ClientConfig.LeafCulling.AUTO;

    private static boolean lastCutout;

    private Foliage() {}

    /** Le mixin de maillage n'appelle que ceci. */
    public static boolean masking() {
        return masking;
    }

    /**
     * Recalcule la décision, une fois par image, et redemande une reconstruction si elle a changé.
     *
     * <p>La reconstruction est le point délicat : sans elle, un joueur qui active les feuilles
     * ajourées garderait à l'écran une géométrie maillée sous l'ancienne règle, avec des trous dans
     * les arbres qu'aucun réglage ne semblerait pouvoir enlever.
     */
    public static void refresh(Minecraft minecraft) {
        ClientConfig.LeafCulling mode = Settings.leafCulling();
        // 26.1 a remplacé l'ancien « rapide / détaillé » global par une option propre aux feuilles :
        // options.cutoutLeaves. Vraie, les feuilles sont découpées et l'on voit au travers ; fausse,
        // elles sont pleines. C'est exactement la question que ce module doit poser, et elle est
        // désormais posée directement plutôt que déduite d'un préréglage graphique d'ensemble.
        boolean cutout = minecraft.options.cutoutLeaves().get();
        boolean wanted = switch (mode) {
            case TOUJOURS -> true;
            case JAMAIS -> false;
            case AUTO -> !cutout;
        };
        boolean changed = wanted != masking || mode != lastMode || cutout != lastCutout;
        masking = wanted;
        lastMode = mode;
        lastCutout = cutout;
        if (changed && minecraft.levelRenderer != null && minecraft.level != null) {
            minecraft.levelRenderer.allChanged();
        }
    }

    /**
     * Ce voisin est-il une feuille, au sens où il cacherait la face qu'on s'apprête à dessiner ?
     *
     * <p>Le test porte sur le voisin et non sur le bloc courant : le mixin n'est posé que sur les
     * feuilles, donc le bloc courant en est déjà une. Deux essences différentes se masquent
     * mutuellement sans difficulté — un chêne contre un bouleau cache tout autant.
     */
    public static boolean hiddenBy(BlockState neighbour) {
        return masking && neighbour.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock;
    }
}
