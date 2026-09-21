package net.irisshaders.iris.mixin;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(DebugScreenEntryList.class)
public class MixinDebugScreenEntriesList {
    @Shadow
    private Map<Identifier, DebugScreenEntryStatus> allStatuses;


    @Inject(method = "rebuildCurrentList", at = @At("HEAD"))
    private void injectSodiumSettings(CallbackInfo ci) {
		if (!this.allStatuses.containsKey(Identifier.fromNamespaceAndPath("iris", "iris"))) {
			this.allStatuses.put(Identifier.fromNamespaceAndPath("iris", "iris"), DebugScreenEntryStatus.IN_OVERLAY);
		}

		if (net.irisshaders.iris.platform.IrisPlatformHelpers.getInstance().isDevelopmentEnvironment() && !this.allStatuses.containsKey(Identifier.fromNamespaceAndPath("iris", "debug"))) {
			this.allStatuses.put(Identifier.fromNamespaceAndPath("iris", "debug"), DebugScreenEntryStatus.IN_OVERLAY);
		}
    }
}

