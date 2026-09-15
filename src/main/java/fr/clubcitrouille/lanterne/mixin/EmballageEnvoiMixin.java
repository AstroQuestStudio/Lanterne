package fr.clubcitrouille.lanterne.mixin;

import java.util.BitSet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

import fr.clubcitrouille.lanterne.core.Emballage;

/**
 * La prise de lecture : le seul endroit où le serveur fabrique un paquet de chunk.
 *
 * <p>Tout le raisonnement est dans {@link Emballage} — ce qui a été mesuré, pourquoi le cache est
 * juste, et ce qui l'invalide. Ce fichier ne fait que substituer le cache au constructeur.
 *
 * <h2>Pourquoi ici, et pas autour de l'envoi</h2>
 *
 * <p>La ligne visée est celle-ci, dans {@code sendChunk} :
 *
 * <pre>
 * connection.send(chunk.getAuxLightManager(chunk.getPos()).sendLightDataTo(
 *         new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null)));
 * </pre>
 *
 * <p>On se place sur le {@code new}, à l'intérieur, et pas sur l'envoi. La différence est
 * importante et elle est de correction, pas de style : {@code sendLightDataTo} n'envoie pas ce
 * paquet seul, il l'emballe dans un lot avec la lumière auxiliaire de NeoForge, prise au moment de
 * l'envoi. Cette charge-là <b>doit</b> rester fraîche. En se plaçant à l'intérieur, on met en cache
 * la partie lourde et commune, et on laisse vanilla refabriquer le lot, le suivi de débogage et
 * l'évènement {@code fireChunkSent} exactement comme avant.
 *
 * <p>Cela laisse également {@code sendNextChunks} intact, où {@code ChunkSenderMixin} pose déjà
 * l'écluse : les deux modules ne se marchent pas dessus, et rien n'oblige à les allumer ensemble.
 *
 * <h2>Sur la cible du redirect</h2>
 *
 * <p>La cible est donnée par son descripteur complet plutôt que par le seul nom de classe. C'est la
 * forme que Mixin vérifie le plus étroitement : le jour où Mojang ajoutera un argument à ce
 * constructeur, l'injection échouera au démarrage au lieu de s'appliquer ailleurs. En revanche
 * {@code tools/verifie_mixins.py} ne sait pas relire un descripteur de constructeur — il ne
 * contrôle ici que {@code sendChunk} et l'existence de {@code PlayerChunkSender}.
 */
@Mixin(PlayerChunkSender.class)
public abstract class EmballageEnvoiMixin {

    /**
     * Le paquet du chunk : celui qu'on avait, ou un neuf qu'on garde.
     *
     * <p>La méthode est statique parce que {@code sendChunk} l'est. Les quatre arguments sont ceux
     * du constructeur remplacé, et on les transmet tels quels : {@link Emballage} refuse lui-même
     * de mettre en cache les formes qu'il ne sait pas réutiliser.
     */
    @Redirect(
            method = "sendChunk",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/world/level/chunk/LevelChunk;"
                            + "Lnet/minecraft/world/level/lighting/LevelLightEngine;"
                            + "Ljava/util/BitSet;Ljava/util/BitSet;)"
                            + "Lnet/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket;"))
    private static ClientboundLevelChunkWithLightPacket lanterne$reuseChunkPacket(
            LevelChunk chunk, LevelLightEngine lumieres, BitSet filtreCiel, BitSet filtreBlocs) {
        return Emballage.paquet(chunk, lumieres, filtreCiel, filtreBlocs);
    }
}
