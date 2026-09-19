package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;

import fr.clubcitrouille.lanterne.core.Leurre;
import fr.clubcitrouille.lanterne.core.network.AutominerPresence;

/**
 * Le seul point où {@link Leurre} apprend QUI reçoit — voir sa Javadoc de classe, section « Le
 * signal retenu », pour tout le raisonnement.
 *
 * <h2>Pourquoi ici, précisément</h2>
 *
 * <p>{@code LeurreEmballageMixin} redirige {@code LevelChunkSection.getSerializedSize()} et
 * {@code .write()}, appelés depuis l'intérieur du constructeur de {@code
 * ClientboundLevelChunkPacketData} — deux méthodes STATIQUES qui ne reçoivent que {@code LevelChunk}
 * en argument (vérifié par {@code javap -p -c -constants}, voir la Javadoc de {@link Leurre}) :
 * aucun joueur, aucune connexion, nulle part dans cette pile d'appels.
 *
 * <p>{@code PlayerChunkSender.sendChunk} — la méthode privée statique que {@code EmballageEnvoiMixin}
 * redirige déjà pour son propre cache de paquet — reçoit, elle, {@code ServerGamePacketListenerImpl}
 * en premier argument (signature vérifiée par {@code javap -p} sur le jar patché 26.3 : {@code
 * private static void sendChunk(ServerGamePacketListenerImpl, ServerLevel, LevelChunk)}). C'est le
 * point le plus proche de la construction du paquet où la connexion cible est connue — remonter plus
 * haut (jusqu'à {@code sendNextChunks}, où {@code ChunkSenderMixin} pose déjà l'écluse) ne
 * rapprocherait de rien, et redescendre est structurellement impossible : rien entre les deux ne
 * porte l'information.
 *
 * <p>{@code javap -rl "ClientboundLevelChunkWithLightPacket"} sur l'intégralité de
 * {@code net.minecraft.server} (698 classes extraites du jar patché) ne trouve QU'UN SEUL appelant du
 * constructeur : {@code PlayerChunkSender}. Ce mixin couvre donc, en pratique, tous les envois réels
 * de chunk — {@code lab/Palissade}, qui appelle ce même constructeur directement pour sa mesure,
 * reste délibérément en dehors (voir sa Javadoc) et sert donc de témoin « connexion inconnue ».
 *
 * <h2>Le repli est du côté sûr</h2>
 *
 * <p>{@code Leurre.EXEMPT} vaut {@code false} par défaut (voir {@code ThreadLocal.withInitial}). Un
 * chemin de construction de paquet qui ne passerait JAMAIS par ce mixin — un mod tiers qui
 * appellerait le constructeur autrement, par exemple — verrait donc {@link Leurre#voile} continuer à
 * protéger, jamais à exposer. Le défaut d'un signal manqué est une exemption ratée (AutoMiner un peu
 * moins pratique), jamais une fuite (personne ne voit jamais MOINS protégé que prévu).
 *
 * <h2>Sur les deux prises, tête et retour</h2>
 *
 * <p>{@code @At("RETURN")} s'applique à CHAQUE instruction de retour de la méthode, pas seulement à
 * la dernière — {@code sendChunk} n'ayant qu'un chemin de sortie normal, une seule prise suffit ici,
 * mais la garder au lieu d'un simple compteur de profondeur évite une fuite d'état si Mojang ajoute un
 * jour un retour anticipé. Le pire cas d'un déséquilibre tête/retour (exception levée au milieu de la
 * construction) laisse {@code Leurre.EXEMPT} sur son dernier état jusqu'au PROCHAIN appel de
 * {@code sendChunk}, qui le réécrit aussitôt — sans conséquence sur une machine à un seul cœur où
 * {@code sendChunk} ne s'exécute jamais en parallèle de lui-même (même hypothèse que {@code Emballage},
 * voir sa Javadoc).
 */
@Mixin(PlayerChunkSender.class)
public abstract class LeurreConnexionMixin {

    @Inject(method = "sendChunk", at = @At("HEAD"))
    private static void lanterne$beginEnvoi(ServerGamePacketListenerImpl connection,
            ServerLevel level, LevelChunk chunk, CallbackInfo callback) {
        Leurre.beginEnvoi(connection.hasChannel(AutominerPresence.TYPE));
    }

    @Inject(method = "sendChunk", at = @At("RETURN"))
    private static void lanterne$finEnvoi(ServerGamePacketListenerImpl connection,
            ServerLevel level, LevelChunk chunk, CallbackInfo callback) {
        Leurre.finEnvoi();
    }
}
