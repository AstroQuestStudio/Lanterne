package net.irisshaders.iris.mixin;

import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.device.GpuBackend;
import net.irisshaders.iris.Iris;
import org.lwjgl.sdl.SDLMessageBox;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_MessageBoxData;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(value = GlBackend.class, priority = 1010)
public class MixinWindow {
	@Inject(method = "createWindow", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_CreateWindow(Ljava/lang/CharSequence;IIJ)J"))
	private void iris$enableDebugContext(String title, int width, int height, long flags, CallbackInfoReturnable<Long> cir) {
		if (Iris.getIrisConfig().areDebugOptionsEnabled()) {
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_DEBUG_FLAG, 1);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_NO_ERROR, 0);
			Iris.logger.info("OpenGL debug context activated.");
			if (net.irisshaders.iris.compat.sodium.bridge.IrisRenderBridge.noErrorContextEnabled()) {
				SDLMessageBox.SDL_ShowSimpleMessageBox(
					SDLMessageBox.SDL_MESSAGEBOX_WARNING,
					"Iris",
					"Due to a configuration issue, Iris may crash on this launch. " +
						"This has been fixed automatically for the next launch.",
					0L
				);
				net.irisshaders.iris.compat.sodium.bridge.IrisRenderBridge.fixNoErrorContext();
			}
		}
	}
}
