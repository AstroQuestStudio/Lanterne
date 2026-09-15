package fr.clubcitrouille.lanterne.lab;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Une connexion qui accepte tout et n'envoie rien.
 *
 * <h2>Pourquoi il en faut une</h2>
 *
 * <p>Un {@link net.minecraft.server.level.ServerPlayer} ne peut pas exister sans connexion : le
 * serveur lui en demande une pour l'inscrire, puis lui adresse des paquets. Mais un joueur d'essai
 * n'a personne au bout du fil, et le canal réseau qu'il n'a pas ferait échouer le premier envoi.
 *
 * <p>Cette connexion absorbe donc tout ce qu'on lui donne. Ce n'est pas un canal fermé — qui lèverait
 * des erreurs — c'est un canal qui accepte, ne transmet pas, et se déclare en bonne santé.
 *
 * <h2>Ce que cela ne fausse pas, et ce que cela fausse</h2>
 *
 * <p>Tout ce qui se passe <b>avant</b> l'envoi reste mesuré : la décision de suivre une entité, la
 * construction des paquets de position, la sérialisation. C'est là que se trouve le coût processeur
 * du réseau côté serveur, et c'est ce qu'on veut observer.
 *
 * <p>Ce qui n'est pas mesuré : la compression, le chiffrement et l'écriture sur la socket. Ce sont
 * des coûts réels, mais ils dépendent de la machine et du réseau du joueur, pas du serveur — et les
 * mesurer ici donnerait un chiffre qui ne vaudrait que pour cette machine-ci.
 *
 * <p>Le compteur de paquets absorbés est donc la mesure honnête de l'effet réseau : <b>combien de
 * paquets le serveur a décidé d'émettre</b>, ce qui est exactement la grandeur sur laquelle un mod
 * peut agir.
 */
public final class SilentConnection extends Connection {
    private long absorbed;
    private EmbeddedChannel embedded;

    public SilentConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    /**
     * Donne à la connexion un canal en mémoire.
     *
     * <h2>Pourquoi surcharger {@code send} ne suffisait pas</h2>
     *
     * <p>La première version se contentait de neutraliser les trois surcharges de {@code send}. Elle
     * a échoué à l'inscription du premier joueur, sur un message sans ambiguïté :
     *
     * <pre>
     * Cannot invoke "io.netty.channel.Channel.writeAndFlush(Object)" because "this.channel" is null
     * </pre>
     *
     * <p>Le serveur ne passe pas toujours par {@code send} : certaines étapes de mise en place —
     * changement de protocole, purge de file — s'adressent directement au canal. Intercepter les
     * portes qu'on connaît laisse toujours celles qu'on ignore.
     *
     * <p>On fournit donc un vrai canal, mais un canal <b>en mémoire</b> : {@code EmbeddedChannel}
     * accepte tout, n'ouvre aucune socket, et garde les paquets dans une file qu'on vide. Le serveur
     * n'a plus rien d'exceptionnel à traiter — ce qui est très exactement le but d'une doublure.
     */
    public void attach() {
        // Le canal appelle « channelActive » sur son gestionnaire à la construction, ce qui est la
        // façon dont Connection reçoit normalement son canal. On ne force rien : on emprunte le
        // chemin prévu.
        embedded = new EmbeddedChannel(this);
    }

    /**
     * Vide la file du canal.
     *
     * <p>Sans cela, les paquets destinés à un joueur qui n'existe pas s'entassent en mémoire — et un
     * banc qui mesure les allocations en serait le premier faussé.
     */
    public void drain() {
        if (embedded != null) {
            embedded.outboundMessages().clear();
            embedded.inboundMessages().clear();
        }
    }

    @Override
    public void send(Packet<?> packet) {
        absorb(packet);
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
        absorb(packet);
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        absorb(packet);
    }

    /**
     * Avale le paquet, mais retient ce qu'une doublure ne peut pas ignorer sans mentir.
     *
     * <h2>Le seul paquet qu'on ne peut pas se contenter de jeter</h2>
     *
     * <p>Une doublure ordinaire n'a rien à répondre : elle encaisse ses chunks et ses positions
     * d'entités sans qu'il en résulte quoi que ce soit. Une téléportation est différente, parce que
     * le serveur <b>attend un accusé de réception</b> : tant qu'il ne l'a pas, {@code
     * updateAwaitingTeleport()} fait ignorer toute position annoncée par ce joueur — seule la
     * rotation passe.
     *
     * <p>Une épreuve qui pousse des paquets de mouvement dans une doublure jamais accusée mesurerait
     * donc <b>zéro</b>, sans rien signaler : le serveur jetterait chaque paquet en silence, et le
     * relevé annoncerait fièrement qu'aucun retour en arrière n'a eu lieu. C'est exactement la
     * catégorie de banc que ce dépôt a déjà rendue « conforme » sur une scène vide, cinq fois.
     *
     * <p>On retient donc le numéro, et l'épreuve qui en a besoin y répond comme le ferait un vrai
     * client. Les doublures qui ne bougent pas ne s'en servent jamais et n'en souffrent pas.
     */
    private void absorb(Packet<?> packet) {
        absorbed++;
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket move) {
            lastTeleportId = move.id();
        } else if (packet instanceof net.minecraft.network.protocol.common.ClientboundKeepAlivePacket alive) {
            lastKeepAlive = alive.getId();
        }
    }

    /**
     * Numéro de la dernière téléportation que le serveur a voulu imposer, ou -1.
     *
     * <p>Voir {@link #absorb} : c'est ce à quoi un vrai client répondrait, et sans quoi le serveur
     * n'écoute plus ce joueur.
     */
    public int lastTeleportId() {
        return lastTeleportId;
    }

    /**
     * Toujours ouverte.
     *
     * <p>Sans cela, le serveur constaterait une connexion morte au premier tick et retirerait le
     * joueur — ce qui est très exactement ce qu'on cherche à éviter.
     */
    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void tick() {
        drain(); // ce qu'on n'envoie à personne ne doit pas s'accumuler
    }

    /** Voir {@link #lastTeleportId()}. Moins un tant que le serveur n'en a imposé aucune. */
    private int lastTeleportId = -1;

    /** Voir {@link #lastKeepAlive()}. Zéro tant que le serveur n'a rien demandé. */
    private long lastKeepAlive;

    /**
     * Le dernier défi de présence que le serveur a envoyé, ou zéro.
     *
     * <p>Une doublure dont la connexion est réellement tickée doit y répondre : passé quinze
     * secondes sans réponse, le serveur la déconnecte pour délai dépassé — au beau milieu du relevé,
     * et sans que le tableau ne dise pourquoi il s'est arrêté.
     */
    public long lastKeepAlive() {
        return lastKeepAlive;
    }

    /** Nombre de paquets que le serveur a voulu envoyer à ce joueur. */
    public long absorbed() {
        return absorbed;
    }

    public void resetCount() {
        absorbed = 0L;
    }
}
