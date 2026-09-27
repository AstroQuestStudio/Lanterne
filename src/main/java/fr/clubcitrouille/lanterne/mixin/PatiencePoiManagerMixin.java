package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;

import fr.clubcitrouille.lanterne.core.PatiencePoi;

/** Toute pose, tout retrait et toute libération de POI lèvent les attentes de {@link PatiencePoi}. */
@Mixin(PoiManager.class)
public abstract class PatiencePoiManagerMixin {
    @Inject(method = "add", at = @At("HEAD"))
    private void lanterne$poiPose(BlockPos pos, Holder<PoiType> type, CallbackInfoReturnable<PoiRecord> callback) {
        PatiencePoi.changement();
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void lanterne$poiRetire(BlockPos pos, CallbackInfo callback) {
        PatiencePoi.changement();
    }

    /** Un villageois mort ou parti rend son lit ou son poste. */
    @Inject(method = "release", at = @At("HEAD"))
    private void lanterne$poiLibere(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        PatiencePoi.changement();
    }
}
