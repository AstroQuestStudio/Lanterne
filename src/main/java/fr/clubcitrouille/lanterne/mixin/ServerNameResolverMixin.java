package fr.clubcitrouille.lanterne.mixin;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.google.common.net.InetAddresses;

import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Pas de résolution DNS inversée pour une adresse qui est déjà une IP littérale.
 *
 * <h2>Idée de <a href="https://github.com/Fallen-Breath/fast-ip-ping">Fast IP Ping</a> (Fallen_Breath,
 * LGPL-3.0-only — voir {@code NOTICE.md}), point d'accroche réécrit pour la 26.3</h2>
 *
 * <p>{@code ServerAddressResolver.SYSTEM} — le résolveur système appelé par le corps vanilla de
 * {@link ServerNameResolver#resolveAddress} — appelle {@code InetAddress.getByName(hôte)} puis,
 * <b>dans le même appel</b>, interroge le nom d'hôte du résultat pour construire le
 * {@link ResolvedServerAddress} qu'il retourne. Pour une IP littérale, cette interrogation déclenche
 * une vraie recherche DNS inversée — coûteuse (plusieurs secondes) et vaine, puisqu'une IP de VPS n'a
 * en général aucun nom à trouver.
 *
 * <p><b>Version précédente de ce correctif, insuffisante</b> : patcher le résultat après le retour de
 * la méthode vanilla (injection {@code @At("RETURN")}) arrive trop tard — la recherche DNS a déjà eu
 * lieu <em>à l'intérieur</em> du corps de la méthode originale avant même que notre code s'exécute.
 * Mesuré en production avec une instrumentation temporaire : {@code resolveAddress()} vanilla mettait
 * ~9,5 s à retourner, notre ancien patch s'appliquait ensuite en 0 ms sur un résultat déjà obtenu
 * lentement. Preuve dans {@code notes/nuit-17-18-septembre-2026.md} et l'historique de ce fichier.
 *
 * <p><b>Correction actuelle</b> : injection en tête de méthode ({@code @At("HEAD")}), qui court-circuite
 * entièrement le corps vanilla pour une IP littérale — celui-ci n'est alors jamais exécuté, donc la
 * recherche DNS inversée qu'il contient n'a jamais lieu. On construit nous-mêmes l'adresse résolue via
 * {@code InetAddresses.forString} (parsing pur, aucun accès réseau) puis
 * {@code InetAddress.getByAddress(hôte, octets)}, qui préremplit le nom d'hôte à la construction.
 *
 * <p>{@link ServerNameResolver#resolveAddress} est l'unique point par lequel passent à la fois
 * {@code ConnectScreen} (la connexion) et {@code ServerStatusPinger} (le ping de la liste des
 * serveurs) — vérifié par lecture du vrai jar client de cette version, pas des sources décompilées
 * périmées. Un seul point d'accroche suffit donc pour les deux usages.
 */
@Mixin(ServerNameResolver.class)
public abstract class ServerNameResolverMixin {

    // Instrumentation temporaire conservee : confirme que le court-circuit HEAD ramene bien
    // resolveAddress() a quelques millisecondes au lieu des ~9,5 s vanilla. A retirer une fois
    // le gain confirme par le joueur en conditions reelles.
    @Inject(method = "resolveAddress", at = @At("HEAD"), cancellable = true)
    private void lanterne$skipReverseDnsForLiteralIp(ServerAddress address,
            CallbackInfoReturnable<Optional<ResolvedServerAddress>> callback) {
        String host = address.getHost();
        if (!InetAddresses.isInetAddress(host)) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            byte[] octets = InetAddresses.forString(host).getAddress();
            InetAddress patched = InetAddress.getByAddress(host, octets);
            callback.setReturnValue(Optional.of(
                    ResolvedServerAddress.from(new InetSocketAddress(patched, address.getPort()))));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Lanterne.LOG.info("[CHRONO-PING] court-circuit HEAD pour IP litterale '{}' : {} ms, corps vanilla jamais execute", host, ms);
        } catch (UnknownHostException ignored) {
            // Tableau d'octets de longueur inattendue : on laisse le corps vanilla s'executer
            // normalement (pas d'annulation) plutot que de casser la connexion.
            Lanterne.LOG.warn("[CHRONO-PING] court-circuit ECHOUE pour '{}', repli sur le chemin vanilla", host);
        }
    }
}
