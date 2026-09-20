package net.irisshaders.iris.platform;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.nio.file.Path;

public class IrisForgeHelpers implements IrisPlatformHelpers {
	@Override
	public boolean isModLoaded(String modId) {
		return FMLLoader.getCurrent().getLoadingModList().getModFileById(modId) != null;
	}

	boolean HAS_CAMO = isModLoaded("cable_facades");

	/**
	 * "iris" n'existe plus comme fichier de mod separe depuis la fusion dans Lanterne (voir
	 * IRIS-ATTRIBUTION.md) : {@code getModFileById(Iris.MODID)} renvoie null, d'ou le
	 * NullPointerException reel observe au demarrage client le 21/09/2026 des la premiere
	 * ouverture d'un menu qui affiche cette version (Iris.MODID vaut toujours "iris", jamais
	 * change, cf ligne 81 de Iris.java). On renvoie la version de "lanterne" a la place : c'est
	 * desormais le seul fichier de mod qui porte reellement ce code.
	 */
	@Override
	public String getVersion() {
		return FMLLoader.getCurrent().getLoadingModList().getModFileById("lanterne").versionString();
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return !FMLLoader.getCurrent().isProduction();
	}

	@Override
	public Path getGameDir() {
		return FMLPaths.GAMEDIR.get();
	}

	@Override
	public Path getConfigDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	@Override
	public int compareVersions(String currentVersion, String semanticVersion) throws Exception {
		return new DefaultArtifactVersion(currentVersion).compareTo(new DefaultArtifactVersion(semanticVersion));
	}

	@Override
	public KeyMapping registerKeyBinding(KeyMapping keyMapping) {
		IrisForgeMod.KEYLIST.add(keyMapping);
		return keyMapping;
	}

	@Override
	public boolean useELS() {
		return true;
	}

	// TODO find a way to do this without breaking Cable Facades...
	@Override
	public BlockState getBlockAppearance(BlockAndTintGetter level, BlockState state, Direction cullFace, BlockPos pos) {
		return state.getAppearance(level, pos, cullFace, null, null);
	}
}
