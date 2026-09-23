package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le sursis : une expulsion vanilla pure, et un faux positif pur.
 *
 * <h2>Le symptôme, et sa cause exacte</h2>
 *
 * <p>Un joueur qui bouge se fait expulser avec « Invalid move player packet received », sans avoir
 * triché ni volé. La lecture du bytecode de {@code handleMovePlayer} (désassemblage du jar de
 * compilation, {@code ServerGamePacketListenerImpl.class}) montre <b>deux</b> appels distincts à
 * {@code disconnect(Component)} portant ce même message :
 *
 * <ol>
 *   <li>Le premier suit {@code containsInvalidValues(x, y, z, yRot, xRot)} — un NaN ou un infini
 *       réel dans le paquet. Celui-là reste légitime, et ce module n'y touche pas.</li>
 *   <li>Le second suit un test sur {@code receivedPositionThisTick} : si ce drapeau est déjà posé
 *       quand un <b>second</b> paquet de position arrive, le joueur est expulsé — quelle que soit la
 *       validité de sa position.</li>
 * </ol>
 *
 * <p>Un client sain n'envoie jamais deux paquets de position dans la même fenêtre de son plein gré :
 * il en émet un par tick de client, vingt par seconde. Ce qui produit le second, c'est le
 * <b>serveur</b> : en retard d'un tick ou plus, il vide sa file réseau d'un coup au tick suivant, et
 * {@code Connection.tick()} traite alors plusieurs paquets de position légitimes dans le même
 * passage. Le premier pose le drapeau ; le second expulse — pour la seule raison qu'ils sont arrivés
 * ensemble.
 *
 * <h2>Pourquoi ce n'est pas {@link fr.clubcitrouille.lanterne.core.Elastique}</h2>
 *
 * <p>{@code Elastique} élargit des <b>seuils</b> de distance et de vitesse — il laisse donc passer
 * un peu plus qu'avant, borné et proportionnel au retard mesuré. Ce module-ci ne touche à aucun
 * seuil : la position annoncée par le paquet qui <b>est</b> traité reste jugée par
 * {@code isEntityCollidingWithAnythingNew} et par les trois contrôles habituels, exactement comme
 * sans le mod. On se contente de ne pas expulser pour le seul fait d'avoir reçu deux paquets dans le
 * même tick — le second est simplement laissé de côté, comme s'il était arrivé une fraction de
 * seconde plus tard au tick suivant, ce qui du point de vue du client est très exactement ce qui
 * s'est passé.
 *
 * <h2>Pourquoi l'ancrage vise le second appel, et pas le premier</h2>
 *
 * <p>Les deux disconnects portent le même message et la même signature ; {@code ordinal = 1} vise
 * le second, dans l'ordre où {@code handleMovePlayer} les rencontre. Le premier — le contrôle NaN —
 * n'est pas touché : c'est un vrai paquet invalide, et il doit continuer à expulser.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SursisMixin {
    /**
     * Remplace l'expulsion par un simple abandon du paquet en trop.
     *
     * <p>{@code handleMovePlayer} fait un {@code return} juste après cet appel — voir le bytecode,
     * offset 80 après l'offset 77 de l'invocation. Ne pas expulser suffit donc à rendre ce chemin
     * inoffensif : aucune autre instruction de la méthode ne s'exécute pour ce paquet.
     */
    @Redirect(method = "handleMovePlayer",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
                             + "disconnect(Lnet/minecraft/network/chat/Component;)V",
                     ordinal = 1))
    private void lanterne$grantReprieve(ServerGamePacketListenerImpl instance, Component reason) {
        if (!Settings.sursis()) {
            instance.disconnect(reason);
        }
    }
}
