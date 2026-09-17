package fr.clubcitrouille.lanterne.mixin;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.google.common.net.InetAddresses;

import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;

/**
 * Pas de résolution DNS inversée pour une adresse qui est déjà une IP littérale.
 *
 * <h2>Idée de <a href="https://github.com/Fallen-Breath/fast-ip-ping">Fast IP Ping</a> (Fallen_Breath,
 * LGPL-3.0-only — voir {@code NOTICE.md}), point d'accroche réécrit pour la 26.3</h2>
 *
 * <p>{@code ServerAddressResolver.SYSTEM} — le résolveur système — appelle
 * {@code InetAddress.getByName(hôte)}. Pour une IP littérale (« 51.68.x.x »), cet appel ne fait
 * aucune requête réseau : il se contente de découper la chaîne en octets. Mais le nom d'hôte de
 * l'objet retourné reste {@code null}, <b>non résolu</b> — et {@link ResolvedServerAddress#getHostName()}
 * l'interroge, ce qui déclenche <em>alors</em> une vraie recherche DNS inversée, coûteuse et souvent
 * vaine puisqu'une IP littérale n'a le plus souvent aucun nom à trouver. C'est cette recherche
 * paresseuse, pas la connexion elle-même, qui ajoute une à cinq secondes au ping ou à la connexion
 * d'un serveur identifié par IP plutôt que par nom de domaine.
 *
 * <p>Le mod d'origine posait deux à trois mixins — un par appelant de {@code InetAddress.getByName},
 * chacun visant soit une classe anonyme, soit une lambda, deux cibles fragiles d'une version à
 * l'autre. Ici, {@link ServerNameResolver#resolveAddress} est l'unique point par lequel passent à la
 * fois {@code ConnectScreen} (la connexion) et {@code ServerStatusPinger} (le ping de la liste des
 * serveurs) — vérifié dans les sources décompilées de la 26.3, où les deux classes ne connaissent que
 * {@link ServerNameResolver#DEFAULT}. Un seul point d'accroche, une seule méthode publique nommée,
 * suffit donc là où l'original en imposait plusieurs.
 *
 * <p>La correction elle-même : quand l'hôte demandé est une IP littérale, on reconstruit l'adresse
 * résolue avec {@code InetAddress.getByAddress(hôte, octets)} au lieu de {@code getByName} — cette
 * variante donne le nom d'hôte <b>à la construction</b>, ce qui rend {@code getHostName()} immédiat
 * pour le reste de la session, sans jamais consulter le DNS.
 */
@Mixin(ServerNameResolver.class)
public abstract class ServerNameResolverMixin {
    @Inject(method = "resolveAddress", at = @At("RETURN"), cancellable = true)
    private void lanterne$skipReverseDnsForLiteralIp(ServerAddress address,
            CallbackInfoReturnable<Optional<ResolvedServerAddress>> callback) {
        Optional<ResolvedServerAddress> result = callback.getReturnValue();
        if (result.isEmpty() || !InetAddresses.isInetAddress(address.getHost())) {
            return;
        }
        InetSocketAddress resolved = result.get().asInetSocketAddress();
        try {
            InetAddress patched = InetAddress.getByAddress(address.getHost(), resolved.getAddress().getAddress());
            callback.setReturnValue(Optional.of(
                    ResolvedServerAddress.from(new InetSocketAddress(patched, resolved.getPort()))));
        } catch (UnknownHostException ignored) {
            // Tableau d'octets de longueur inattendue : on garde le résultat d'origine, tant pis
            // pour le gain — jamais de connexion cassée pour économiser une recherche DNS.
        }
    }
}
