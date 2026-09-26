package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * La recharge d'un entonnoir, que le jeu garde pour lui — lue par le ticker propre du Trieur.
 *
 * <h2>Pourquoi le Trieur en a besoin</h2>
 *
 * <p>Depuis 26.3.141, {@link fr.clubcitrouille.lanterne.content.trieur.TrieurTransfert} remplace
 * {@code HopperBlockEntity.pushItemsTick} pour le seul Trieur : transfert en vrac par l'API de
 * transfert NeoForge. Il doit pourtant tenir EXACTEMENT la même horloge que vanilla, parce que le
 * reste du jeu continue d'y écrire — un entonnoir vanilla qui pousse dans un Trieur vide lui pose
 * {@code setCooldown(8 - skip)} (voir {@code HopperBlockEntity.tryMoveInItem}), et l'insertion par
 * capacité passe par {@code onTransfer}, qui journalise ce même champ dans une transaction.
 *
 * <p>Or {@code cooldownTime} est privé, et {@code isOnCooldown()} aussi (vérifié dans les sources
 * patchées 26.3.0.7-beta). {@code setCooldown} est public, mais aucun lecteur ne l'est. Plutôt que de
 * tenir un second compteur à côté — qui divergerait au premier retour arrière de transaction, puisque
 * le journal NeoForge réécrit le champ sans passer par {@code setCooldown} — on lit le vrai.
 *
 * <p>{@code tickedGameTime} est écrit pour la même raison : vanilla s'en sert pour décider si un
 * entonnoir qui pousse dans un autre lui accorde un tick d'avance ({@code skipTickCount}). Sans lui,
 * un Trieur resterait figé au tick zéro et fausserait ce calcul chez ses voisins.
 *
 * <p>Même statut que {@link ItemAgeAccessor} : un accesseur n'altère aucun corps de méthode, et s'il
 * cesse de correspondre à une version future, le chargement échoue bruyamment.
 */
@Mixin(HopperBlockEntity.class)
public interface HopperCooldownAccessor {
    @Accessor("cooldownTime")
    int lanterne$cooldownTime();

    @Accessor("tickedGameTime")
    void lanterne$setTickedGameTime(long gameTime);
}
