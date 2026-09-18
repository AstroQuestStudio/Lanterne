package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import fr.clubcitrouille.lanterne.core.Loterie;

/**
 * La section de bloc, dotée du compteur qui lui manque pour la loterie.
 *
 * <p>Le raisonnement complet vit dans {@link Loterie}. Ce fichier est la plomberie : deux points
 * d'accroche, choisis pour couvrir les deux seules façons dont le contenu d'une section change.
 *
 * <h2>Pourquoi deux points, et pas un seul</h2>
 *
 * <ul>
 *   <li>{@code setBlockState(int, int, int, BlockState, boolean)} — <b>le</b> point de passage unique
 *       de toute pose ou casse de bloc en jeu. {@code Level.setBlock} y descend toujours, quel que soit
 *       l'appelant : joueur, piston, structure, génération différée. Une injection en queue lit l'ancien
 *       état (rendu par la méthode) et le nouveau (son paramètre), et ne touche aux compteurs que si
 *       {@link Loterie#eligible} a changé — la grande majorité des poses n'y changent rien.</li>
 *   <li>{@code recalcBlockCounts()} — le seul autre chemin. Vérifié par lecture du bytecode du
 *       constructeur {@code LevelChunkSection(PalettedContainer, PalettedContainerRO)} : c'est lui que
 *       {@code SerializableChunkData} appelle pour peupler une section depuis le disque, en écrivant le
 *       conteneur en bloc plutôt que position par position — {@code setBlockState} n'y est jamais
 *       appelé, et sans ce second point les sections rechargées auraient un compteur figé à zéro. La
 *       méthode existe précisément pour que vanilla recompte ses propres totaux après un tel
 *       remplissage brut ; ce module recompte les siens au même instant.</li>
 * </ul>
 *
 * <h2>Ce que ça ne couvre délibérément pas</h2>
 *
 * <p>{@code copy()} — utilisé uniquement pour l'instantané de sauvegarde asynchrone
 * ({@code SerializableChunkData}, jamais tické) — recopie les champs vanilla mais pas ceux-ci : la
 * copie hérite donc d'un total à zéro. Sans conséquence, puisque cette copie ne passe jamais par
 * {@code ServerLevel.tickChunk}. Si un mod tiers venait un jour à la tickeuse quand même, le pire
 * résultat serait un total sous-estimé, donc {@link Loterie#tick} croirait la section plus rare qu'elle
 * ne l'est et pourrait manquer des tirages — jamais en inventer.
 */
@Mixin(LevelChunkSection.class)
public abstract class LoterieSectionMixin implements Loterie.Section {
    /**
     * Positions éligibles au tirage aléatoire (bloc OU fluide), toutes couches confondues.
     *
     * <p>Une union, jamais la somme de {@code tickingBlockCount} et {@code tickingFluidCount} :
     * vanilla les compte séparément, et une position immergée qui tique des deux côtés compterait deux
     * fois. Voir la classe {@link Loterie}.
     */
    @Unique
    private int lanterne$total;

    /**
     * Éligibles par couche horizontale (seize couches de deux cent cinquante-six positions).
     *
     * <p>C'est elle qui borne le parcours d'un coup à une seule couche au lieu de la section entière.
     * Soixante-quatre octets par section — négligeable à côté des quatre mille octets qu'occupe déjà le
     * conteneur en palette.
     */
    @Unique
    private final int[] lanterne$parCouche = new int[16];

    @Override
    public int lanterne$total() {
        return this.lanterne$total;
    }

    @Override
    public void lanterne$total(int total) {
        this.lanterne$total = total;
    }

    @Override
    public int[] lanterne$parCouche() {
        return this.lanterne$parCouche;
    }

    /**
     * La signature complète est donnée parce que {@code setBlockState} existe en deux exemplaires dans
     * cette classe (avec et sans le booléen) ; le second délègue au premier, qui est donc le seul qui
     * voie réellement chaque changement.
     */
    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)"
            + "Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void lanterne$ajuste(int x, int y, int z, BlockState nouveau, boolean verifie,
            CallbackInfoReturnable<BlockState> retour) {
        Loterie.ajuste(this, y, retour.getReturnValue(), nouveau);
    }

    @Inject(method = "recalcBlockCounts", at = @At("RETURN"))
    private void lanterne$reconstruit(CallbackInfo retour) {
        Loterie.reconstruit(this, (LevelChunkSection) (Object) this);
    }
}
