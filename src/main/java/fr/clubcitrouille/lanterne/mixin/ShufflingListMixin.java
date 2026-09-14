package fr.clubcitrouille.lanterne.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.world.entity.ai.behavior.ShufflingList;

import fr.clubcitrouille.lanterne.core.ShufflingListAccess;

/**
 * Le contenu brut d'une {@code ShufflingList}, rendu lisible par indice.
 *
 * <p>Ce mixin n'enlève ni ne remplace rien : il <b>ajoute</b> deux méthodes. Le {@code stream()} et
 * l'{@code iterator()} d'origine restent en place et continuent de servir tous leurs autres
 * appelants dans le jeu. Seul {@code GateBehaviorMixin} emprunte ce chemin-ci.
 *
 * <p>Voir {@link ShufflingListAccess} pour la raison d'être de l'interface.
 */
@Mixin(ShufflingList.class)
public abstract class ShufflingListMixin<U> implements ShufflingListAccess<U> {
    @Shadow
    @Final
    protected List<ShufflingList.WeightedEntry<U>> entries;

    @Override
    public int lanterne$size() {
        return this.entries.size();
    }

    @Override
    public U lanterne$at(int index) {
        return this.entries.get(index).getData();
    }
}
