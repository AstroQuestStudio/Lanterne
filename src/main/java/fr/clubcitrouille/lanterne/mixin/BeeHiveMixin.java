package fr.clubcitrouille.lanterne.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.level.Level;

import fr.clubcitrouille.lanterne.core.Digue;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'abeille qui ne fait plus charger le chunk de sa ruche — et qui s'en souvient quand même.
 *
 * <h2>Le chargement</h2>
 *
 * <p>{@code Bee.getBeehiveBlockEntity()} (ligne 470 de {@code Bee.java}) fait ceci :
 *
 * <pre>
 * return this.isTooFarAway(this.hivePos) ? null
 *      : this.level().getBlockEntity(this.hivePos) instanceof BeehiveBlockEntity hive ? hive : null;
 * </pre>
 *
 * <p>{@code Level.getBlockEntity} passe par {@code getChunkAt(pos)}, donc par
 * {@code getChunk(x, z, FULL, true)} : <b>chargement synchrone</b>. Et {@code isTooFarAway} vaut
 * quarante-huit blocs, soit trois chunks — sur un serveur en distance dix, une abeille qui tick au
 * bord de la zone simulée peut parfaitement viser une ruche hors de la zone chargée. Elle y fait
 * alors descendre le fil du serveur au disque, au moins une fois toutes les vingt ticks.
 *
 * <h2>Le défaut du remède, et pourquoi ce mixin ne s'arrête pas là</h2>
 *
 * <p>{@code ServerCore} redirige ce seul test, et c'est insuffisant. Vingt lignes plus haut,
 * {@code Bee.aiStep()} contient :
 *
 * <pre>
 * if (this.tickCount % 20 == 0 &amp;&amp; !this.isHiveValid()) {
 *     this.hivePos = null;
 * }
 * </pre>
 *
 * <p>Refuser le chargement rend donc {@code isHiveValid()} faux, et <b>l'abeille oublie sa ruche pour
 * de bon</b>. Ce n'est pas un retard, c'est une perte : elle ne rentrera plus, la ruche ne produira
 * plus de miel, et rien ne le signalera. C'est très exactement le genre de dégât qu'un banc de
 * vitesse applaudit.
 *
 * <p>La seconde injection le corrige. Quand le chunk de la ruche n'est pas là, on ne dit pas « ta
 * ruche n'existe pas » mais « je ne sais pas » — et l'on ne jette rien. Si la ruche a réellement été
 * cassée pendant l'absence, le chunk finira par revenir, {@code isHiveValid()} rendra faux pour de
 * vrai, et l'oubli aura lieu à ce moment-là. <b>Le remède se corrige tout seul ; l'oubli, non.</b>
 *
 * <h2>Ce qu'on perd quand même</h2>
 *
 * <p>Une abeille dont la ruche est hors du chargé ne rentre pas et ne dépose pas son nectar tant que
 * le chunk n'est pas revenu. En pratique le joueur qui regarde la ruche charge le chunk, donc le cas
 * ne se produit que là où personne ne regarde.
 */
@Mixin(Bee.class)
public abstract class BeeHiveMixin extends Animal {
    @Shadow
    private @Nullable BlockPos hivePos;

    /**
     * Emprunté plutôt que recopié.
     *
     * <p>{@code isTooFarAway} vaut quarante-huit blocs dans la 26.2. Réécrire ce nombre ici le
     * figerait : une version où Mojang le change laisserait la garde ci-dessous protéger une ruche
     * que le jeu, lui, aurait déjà lâchée. On appelle donc la méthode du jeu.
     */
    @Shadow
    private boolean isTooFarAway(BlockPos targetPos) {
        throw new AssertionError();
    }

    private BeeHiveMixin(EntityType<? extends Animal> type, Level level) {
        super(type, level);
    }

    /**
     * « Trop loin » devient « trop loin, ou pas chargée ».
     *
     * <p>On modifie la valeur du test plutôt que le {@code getBlockEntity} qui suit, pour une raison
     * de justesse : le jeu écrit une expression ternaire, et rendre {@code null} au milieu ferait
     * passer le {@code instanceof} sur {@code null} — ce qui marche, mais tient à ce que l'écriture
     * de Mojang ne change pas. Le booléen, lui, a une seule lecture possible.
     */
    @ModifyExpressionValue(
            method = "getBeehiveBlockEntity",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/animal/bee/Bee;isTooFarAway"
                            + "(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean lanterne$hiveOnlyIfLoaded(boolean tooFarAway) {
        if (tooFarAway || !Settings.digue()) {
            return tooFarAway;
        }
        BlockPos hive = this.hivePos;
        if (hive == null || Digue.present(this.level(), hive)) {
            return false;
        }
        Digue.noteBee();
        return true;
    }

    /**
     * Ne pas savoir n'est pas savoir que non.
     *
     * <p>{@code LANTERNE_BREAK_DIGUE=1} retire cette garde et rétablit le comportement de
     * {@code ServerCore}. L'épreuve {@code lab.Levee} doit alors annoncer des abeilles sans ruche.
     */
    @ModifyExpressionValue(
            method = "aiStep",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/animal/bee/Bee;isHiveValid()Z"))
    private boolean lanterne$keepHiveWhenUnloaded(boolean valid) {
        if (valid || !Settings.digue() || Digue.broken()) {
            return valid;
        }
        BlockPos hive = this.hivePos;
        // Trois conditions, et la deuxième est celle qu'on oublie : si l'abeille s'est éloignée de
        // plus de quarante-huit blocs, vanilla lâche la ruche pour une raison qui n'a RIEN à voir
        // avec le chargement. Se taire ici laisserait l'abeille accrochée à une ruche qu'elle ne
        // peut plus rejoindre, et l'empêcherait d'en chercher une autre — on aurait remplacé un gel
        // par une abeille qui ne rentre jamais.
        return hive != null && !this.isTooFarAway(hive) && !Digue.present(this.level(), hive);
    }
}
