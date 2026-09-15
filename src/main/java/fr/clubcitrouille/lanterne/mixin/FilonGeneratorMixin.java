package fr.clubcitrouille.lanterne.mixin;

import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;

import fr.clubcitrouille.lanterne.lab.Filon;

/**
 * Le chronomètre fin de l'étape des biomes — celle qui paie la construction du {@code NoiseChunk}.
 *
 * <h2>Le détail qui change la lecture de tout le rapport</h2>
 *
 * <p>{@code doCreateBiomes} ({@code NoiseBasedChunkGenerator.java} lignes 90-94) ne fait que deux
 * choses, et la première n'a pas l'air d'appartenir aux biomes :
 *
 * <pre>
 * NoiseChunk noiseChunk = protoChunk.getOrCreateNoiseChunk(…);   // ← construit TOUT le bruit
 * protoChunk.fillBiomesFromNoise(biomeResolver, noiseChunk.cachedClimateSampler(…));
 * </pre>
 *
 * <p>C'est donc l'étape {@code minecraft:biomes} — 22,6 % sur un fil — qui paie les deux décalques
 * d'arbre du constructeur, le remplissage des neuf caches plats du surmonde, la création de
 * l'aquifère et l'allocation des seize tranches d'interpolation. Pas l'étape {@code minecraft:noise}.
 *
 * <p>La conséquence pratique est nette : le module {@code calque} ne pouvait <b>pas</b> apparaître
 * dans les 64 % du bruit, et toute estimation qui l'y plaçait se trompait de colonne.
 *
 * <p>Deux détournements suffisent à séparer les deux moitiés de cette étape. Ils n'ajoutent qu'une
 * lecture d'horloge, et disparaissent quand {@code LANTERNE_FILON} est absent.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class FilonGeneratorMixin {
    @Redirect(
            method = "doCreateBiomes",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getOrCreateNoiseChunk("
                            + "Ljava/util/function/Function;)"
                            + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"))
    private NoiseChunk lanterne$timeBuild(ChunkAccess chunk,
            Function<ChunkAccess, NoiseChunk> factory) {
        if (!Filon.ON) {
            return chunk.getOrCreateNoiseChunk(factory);
        }
        long started = System.nanoTime();
        NoiseChunk built = chunk.getOrCreateNoiseChunk(factory);
        Filon.add(Filon.CHANTIER, System.nanoTime() - started);
        return built;
    }

    @Redirect(
            method = "doCreateBiomes",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;fillBiomesFromNoise("
                            + "Lnet/minecraft/world/level/biome/BiomeResolver;"
                            + "Lnet/minecraft/world/level/biome/Climate$Sampler;)V"))
    private void lanterne$timeBiomes(ChunkAccess chunk, BiomeResolver resolver,
            Climate.Sampler sampler) {
        if (!Filon.ON) {
            chunk.fillBiomesFromNoise(resolver, sampler);
            return;
        }
        long started = System.nanoTime();
        chunk.fillBiomesFromNoise(resolver, sampler);
        Filon.add(Filon.BIOMES, System.nanoTime() - started);
    }
}
