package fr.clubcitrouille.lanterne.client;

/**
 * Le tampon : immuable chez Mojang, mutable chez NVIDIA — et désormais réglé par Mojang lui-même.
 *
 * <h2>Ce que ce module faisait, et pourquoi il ne fait plus rien</h2>
 *
 * <p>{@code GL_ARB_buffer_storage} alloue un tampon <b>immuable</b> : sa taille et son emplacement
 * ne bougent plus. Quand le jeu réécrit dedans alors que la carte s'en sert encore, le pilote n'a
 * pas le droit de lui donner une autre zone mémoire — il n'a que le choix d'<b>attendre</b>. Sur
 * pilote NVIDIA et sur les Intel de septième génération, cette attente se voit : l'image se pose,
 * puis saute. Sur 26.2, Lanterne rétroportait la correction — détecter ces pilotes à la main et
 * forcer un tampon mutable — parce que Mojang ne le faisait pas encore : sa classe
 * {@code com.mojang.blaze3d.opengl.GlHeuristics} n'avait alors que {@code isGlOnDx12()} et
 * {@code isAmd()}.
 *
 * <p>Depuis la 26.3, {@code GlHeuristics} gagne {@code isNvidia()} et {@code couldBeIntelGen7()} :
 * exactement cette correction, faite par Mojang, dans le jeu lui-même. Le rétroportage n'a donc
 * plus lieu d'être — le garder aurait même risqué de doubler la décision de vanilla plutôt que de
 * simplement ne plus servir à rien. Le mixin qui l'appliquait ({@code BufferStorageMixin}, qui
 * redirigeait une lecture de champ précise dans {@code BufferStorage.create}) est retiré ; il ciblait
 * en plus un motif de bytecode que le correctif de Mojang a très probablement fait disparaître,
 * ce qui l'aurait fait échouer au chargement plutôt que de simplement devenir inutile.
 *
 * <p>Cette classe reste comme trace de la décision plutôt que d'être supprimée en bloc — voir
 * l'historique Git pour l'implémentation retirée (détection de pilote, log de décision).
 */
public final class Tampon {
    private Tampon() {}

    /** Toujours faux : voir la javadoc de classe. Vanilla décide seul, désormais correctement. */
    public static boolean forceMutable() {
        return false;
    }
}
