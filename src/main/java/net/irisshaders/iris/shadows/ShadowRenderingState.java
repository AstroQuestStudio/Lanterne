package net.irisshaders.iris.shadows;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeCollector;

public class ShadowRenderingState {
	public static boolean areShadowsCurrentlyBeingRendered() {
		return ShadowFlags.active;
	}

	public static int getRenderDistance() {
		return ShadowFlags.renderDistance;
	}
}
