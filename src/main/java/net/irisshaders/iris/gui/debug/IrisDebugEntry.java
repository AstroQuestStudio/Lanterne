package net.irisshaders.iris.gui.debug;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

public class IrisDebugEntry implements DebugScreenEntry {
	@Override
	public void display(DebugScreenDisplayer debugScreenDisplayer, @Nullable Level level, @Nullable LevelChunk levelChunk, @Nullable LevelChunk levelChunk2) {
		debugScreenDisplayer.addToGroup(Identifier.fromNamespaceAndPath("iris", "name"), "[" + Iris.MODNAME + "] Version: " + Iris.getFormattedVersion());

		if (Iris.getIrisConfig().areShadersEnabled()) {
			debugScreenDisplayer.addToGroup(Identifier.fromNamespaceAndPath("iris", "name"), "[" + Iris.MODNAME + "] Shaderpack: " + Iris.getCurrentPackName() + (Iris.isFallback() ? " (fallback)" : ""));
			Iris.getCurrentPack().ifPresent(pack -> debugScreenDisplayer.addToGroup(Identifier.fromNamespaceAndPath("iris", "name"), "[" + Iris.MODNAME + "] " + pack.getProfileInfo()));
			debugScreenDisplayer.addToGroup(Identifier.fromNamespaceAndPath("iris", "name"), "[" + Iris.MODNAME + "] Color space: " + IrisVideoSettings.colorSpace.name());
			boolean sodiumChunks = net.irisshaders.iris.mixin.IrisMixinPlugin.sodiumPresent();
			if (sodiumChunks) {
				net.irisshaders.iris.shadows.ShadowFlags.active = true;
			}
			if (level != null && sodiumChunks) {
				debugScreenDisplayer.addToGroup(Identifier.fromNamespaceAndPath("iris", "name"), "[" + Iris.MODNAME + "] Shadows: " + java.util.Objects.requireNonNullElse(net.irisshaders.iris.compat.sodium.bridge.IrisRenderBridge.shadowChunksDebugString(), "n/a"));
			}
			if (sodiumChunks) {
				net.irisshaders.iris.shadows.ShadowFlags.active = false;
			}
		} else {
			debugScreenDisplayer.addToGroup(Identifier.fromNamespaceAndPath("iris", "name"), "[" + Iris.MODNAME + "] Shaders are disabled");
		}

		//messages.add(3, "Direct Buffers: +" + iris$humanReadableByteCountBin(iris$directPool.getMemoryUsed()));

	}
}
