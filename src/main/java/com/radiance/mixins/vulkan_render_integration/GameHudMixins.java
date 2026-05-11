package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.profiler.ProfilerOverlay;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class GameHudMixins {

    @Inject(method = "render", at = @At("TAIL"))
    private void radiance$renderProfilerOverlay(DrawContext context, RenderTickCounter tickCounter,
        CallbackInfo ci) {
        ProfilerOverlay.render(context);
    }
}
