package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import fr.clubcitrouille.lanterne.core.Emballage;

/**
 * La prise des biomes, qui est une assurance et pas une nécessité.
 *
 * <h2>Pourquoi les biomes sont dans le paquet</h2>
 *
 * <p>Ce n'est pas évident en lisant {@code ClientboundLevelChunkPacketData}, qui ne parle que de
 * sections. La réponse est un cran plus bas, dans {@code LevelChunkSection.write} :
 *
 * <pre>
 * buffer.writeShort(this.nonEmptyBlockCount);
 * this.states.write(buffer);
 * this.biomes.write(buffer);      // les biomes sont dans le meme tampon que les blocs
 * </pre>
 *
 * <p>Un paquet mis en cache fige donc aussi les biomes. Or un biome se modifie en jeu, par
 * {@code /fillbiome}, et un biome périmé se voit : la couleur de l'herbe, celle de l'eau, le ciel,
 * la pluie ou la neige. Ce n'est pas un bloc fantôme, mais c'est un monde qui n'existe plus.
 *
 * <h2>Pourquoi cette prise est une ceinture, pas le pantalon</h2>
 *
 * <p>{@code FillBiomeCommand} appelle {@code chunk.markUnsaved()} immédiatement après chaque
 * {@code fillBiomesFromNoise}, et {@code EmballageChunkMixin} attrape déjà ce
 * {@code markUnsaved}. La commande de vanilla est donc couverte sans ce fichier.
 *
 * <p>Ce qui ne l'est pas, c'est un mod qui repeindrait des biomes sans marquer le chunk — la
 * méthode ne marque rien par elle-même. Elle est froide : elle ne sert qu'à la génération du monde
 * (sur des {@code ProtoChunk}, où le test d'instance ci-dessous nous fait sortir sans rien faire) et
 * à la commande. La couvrir ne coûte donc rien de mesurable, et referme la seule porte que la
 * lecture du code laissait entrouverte sur les biomes.
 *
 * <h2>Pourquoi sur {@code ChunkAccess} et pas sur {@code LevelChunk}</h2>
 *
 * <p>{@code fillBiomesFromNoise} est déclarée et implémentée sur {@code ChunkAccess} ;
 * {@code LevelChunk} ne la redéfinit pas. Viser la sous-classe n'attraperait rien.
 */
@Mixin(ChunkAccess.class)
public abstract class EmballageBiomeMixin {

    /**
     * Les biomes de ce chunk viennent d'être repeints.
     *
     * <p>Le test d'instance est la sortie du cas courant : pendant la génération du monde, cette
     * méthode tourne sur des {@code ProtoChunk}, qui n'ont jamais été emballés.
     */
    @Inject(
            method = "fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;"
                    + "Lnet/minecraft/world/level/biome/Climate$Sampler;)V",
            at = @At("HEAD"))
    private void lanterne$dropOnBiomeRepaint(
            BiomeResolver resolveur, Climate.Sampler echantillonneur, CallbackInfo callback) {
        if ((Object) this instanceof LevelChunk chunk) {
            Emballage.oublie(chunk);
        }
    }
}
