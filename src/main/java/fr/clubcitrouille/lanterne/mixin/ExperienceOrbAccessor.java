package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.ExperienceOrb;

/**
 * Le nombre d'unités qu'une orbe porte, que le jeu garde privé.
 *
 * <h2>Pourquoi il faut le lire</h2>
 *
 * <p>Une orbe retient deux nombres : la valeur d'une unité — {@code getValue()}, publique — et le
 * nombre d'unités, qui ne l'est pas. L'expérience réellement portée est le produit des deux.
 *
 * <p>Le module de fusion additionne ces comptes. Sans accès au second, on ne pourrait pas vérifier
 * qu'il n'en crée ni n'en perd — et un module qui touche à l'expérience d'un joueur sans pouvoir le
 * prouver n'a pas sa place dans ce mod. C'est la même exigence qui a fait écrire l'épreuve de tir
 * pour les projectiles, laquelle a rattrapé un module qui supprimait le combat.
 */
@Mixin(ExperienceOrb.class)
public interface ExperienceOrbAccessor {
    @Accessor("count")
    int lanterne$count();
}
