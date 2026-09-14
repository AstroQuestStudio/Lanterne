package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.util.Util;

import fr.clubcitrouille.lanterne.core.Threads;

/**
 * Le dimensionnement des réserves de fils, refait pour une machine à quota.
 *
 * <h2>Pourquoi ce point d'accroche-ci</h2>
 *
 * <p>{@code Util.maxAllowedExecutorThreads()} est la source unique : {@code makeExecutor} l'appelle
 * pour bâtir chacune de ses réserves, et tout autre code qui veut savoir combien de fils le jeu
 * s'autorise passe par elle. Une seule injection couvre donc l'ensemble, sans avoir à connaître le
 * détail de chaque réserve.
 *
 * <h2>Le plafond du jeu est conservé</h2>
 *
 * <p>La valeur d'origine est celle que le jeu <em>s'était fixée</em> — elle tient déjà compte de
 * {@code -Dmax.bg.threads}, qu'un administrateur a pu régler. On ne la dépasse jamais : voir
 * {@link Threads#budget(int)}. Un réglage explicite de l'hébergeur reste donc maître.
 *
 * <h2>Pourquoi il n'y a pas d'interrupteur ordinaire ici</h2>
 *
 * <p>Tous les autres modules du mod passent par {@code Settings}, et s'éteignent avec lui. Celui-ci
 * ne le peut pas : les réserves sont bâties au premier chargement de {@code Util}, c'est-à-dire
 * <b>avant</b> que NeoForge n'ait lu la moindre configuration. Un test sur {@code Settings} y
 * répondrait toujours par la valeur par défaut, ce qui donnerait l'illusion d'un interrupteur qui
 * n'en est pas un.
 *
 * <p>Le réglage est donc lu dans l'environnement, et {@code LANTERNE_THREADS=vanilla} rend la main
 * au jeu — c'est ce qui permet de mesurer ce module en comparant deux exécutions.
 *
 * @see Threads
 */
@Mixin(Util.class)
public abstract class ThreadCountMixin {
    @Inject(method = "maxAllowedExecutorThreads", at = @At("RETURN"), cancellable = true)
    private static void lanterne$sizeForSmallServers(CallbackInfoReturnable<Integer> callback) {
        if (Threads.standDown()) {
            return;
        }
        callback.setReturnValue(Threads.budget(callback.getReturnValue()));
    }
}
