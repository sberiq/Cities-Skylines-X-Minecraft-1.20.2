package dev.citiescraft.minecraft.mixin;

import dev.citiescraft.minecraft.FrameExporter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "render(FJZ)V", at = @At("TAIL"))
    private void citiescraft$exportFinalFrame(float tickDelta, long startTime, boolean tick, CallbackInfo callbackInfo) {
        FrameExporter.afterRender(MinecraftClient.getInstance());
    }
}
