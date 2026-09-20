package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;

/**
 * Coupe le kick anti-triche vanilla "Flying is not enabled on this server" / "was kicked for
 * floating too long" a la racine, pour tout le monde.
 *
 * <h2>Le vrai declencheur, verifie en bytecode</h2>
 *
 * <p>{@code ServerGamePacketListenerImpl.getMaximumFlyingTicks(Entity)} — verifie par javap sur le
 * jar 26.3.0.7-beta reel — calcule le nombre de ticks tolere en l'air comme
 * {@code ceil(80 * max(0.08 / gravite, 1))}, soit 80 ticks (4 secondes) avec la gravite par defaut.
 * Le seul cas ou vanilla desactive deja ce compte est une gravite quasi nulle
 * ({@code < 9.999999747378752E-6}), auquel cas la methode renvoie directement
 * {@code Integer.MAX_VALUE} — c'est cette meme valeur de sortie que ce mixin force
 * <b>inconditionnellement</b>, sans toucher a la gravite ni a {@code Abilities.mayfly}.
 *
 * <h2>Pourquoi ce geste plutot que [[Envol]]</h2>
 *
 * <p>{@code core.Envol} ne fait que reappliquer {@code mayfly} aux joueurs explicitement autorises
 * par {@code /lanterne vol} — un filet par-dessus l'autorisation vanilla normale, pas une
 * suppression du controle lui-meme. Il reste utile pour le double-saut en vol reel. Mais le kick de
 * vol a aussi frappe des joueurs SANS cette autorisation, en jeu normal (signale le 20/09/2026) —
 * probablement de la latence ou un saut de tick, vanilla ne faisant aucune difference entre "vraie
 * triche" et "mouvement vertical legitime pas retombe assez vite". Plutot que de traquer la cause
 * exacte, la consigne du patron est de couper ce controle completement : cette methode est le seul
 * point d'entree des DEUX variantes du kick ({@code clientIsFloating} et
 * {@code clientVehicleIsFloating} l'appellent toutes les deux, verifie en bytecode) — un seul
 * mixin suffit pour les deux.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SansVolKickMixin {

    @Inject(method = "getMaximumFlyingTicks", at = @At("HEAD"), cancellable = true)
    private void lanterne$jamaisDeKick(Entity entity, CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(Integer.MAX_VALUE);
    }
}
