package com.minelatino.afkfarm.forge.mixin;

import com.minelatino.afkfarm.AfkRuntimePolicy;
import com.minelatino.afkfarm.client.AfkFarmClient;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FramerateLimitTracker.class, priority = 500)
public abstract class ForgeFramerateLimitTrackerMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void minelatinoAfk$keepBackgroundFps(CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(AfkRuntimePolicy.framerateLimit(
                AfkFarmClient.instance().active(), callback.getReturnValue()));
    }
}
