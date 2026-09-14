package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import fr.clubcitrouille.lanterne.core.Moulds;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Les formes de collision, partagées entre les états de blocs qui décrivent le même volume.
 *
 * <p>Le raisonnement et les limites sont dans {@link Moulds}.
 *
 * <h2>Dans le constructeur du cache, et non après</h2>
 *
 * <p>Le mod dont ce module s'inspire agit <em>après</em> {@code initCache}, ce qui l'oblige à
 * retrouver le champ {@code cache} de l'état — un champ privé, d'un type interne privé, que Mixin
 * ne sait pas déclarer. Il y parvient par réflexion : nom de champ calculé selon la plateforme,
 * {@code MethodHandle}, exception encapsulée.
 *
 * <p>Rien de tout cela n'est nécessaire si l'on se place <b>dans le constructeur du cache</b>. Les
 * deux champs y sont directement visibles, {@code @Mutable} lève leur caractère final, et le type
 * privé n'a jamais besoin d'être nommé ailleurs que dans l'annotation. Le tout est vérifié au
 * chargement : si Mojang renomme un champ, le mod refuse de démarrer au lieu d'échouer au premier
 * bloc posé.
 *
 * <h2>Quand cela s'exécute</h2>
 *
 * <p>Un cache d'état n'est construit qu'au chargement des registres, jamais en jeu. Toute la
 * dépense de comparaison est donc payée une fois au démarrage, et l'économie dure toute la partie.
 * Une fois le monde chargé, ce module n'exécute plus une seule instruction.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache")
public abstract class StateCacheDedupMixin {
    @Shadow
    @Final
    @Mutable
    private VoxelShape collisionShape;

    @Shadow
    @Final
    @Mutable
    private boolean[] faceSturdy;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lanterne$share(BlockState state, CallbackInfo callback) {
        if (!Settings.moulds()) {
            return;
        }
        this.collisionShape = Moulds.canonical(this.collisionShape);
        this.faceSturdy = Moulds.canonical(this.faceSturdy);
    }
}
