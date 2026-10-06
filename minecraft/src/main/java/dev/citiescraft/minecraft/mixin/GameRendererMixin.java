package dev.citiescraft.minecraft.mixin;

import dev.citiescraft.minecraft.FrameExporter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "renderWorld(FJLnet/minecraft/client/util/math/MatrixStack;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/WorldRenderer;render(Lnet/minecraft/client/util/math/MatrixStack;FJZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;Lorg/joml/Matrix4f;)V",
                    shift = At.Shift.AFTER))
    private void citiescraft$captureWorldLayer(float tickDelta, long limitTime, MatrixStack matrices,
                                                CallbackInfo callbackInfo) {
        FrameExporter.afterWorldRender(MinecraftClient.getInstance());
    }

    @Inject(method = "renderHand(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/Camera;F)V",
            at = @At("HEAD"))
    private void citiescraft$beginHandLayer(MatrixStack matrices, Camera camera, float tickDelta,
                                             CallbackInfo callbackInfo) {
        FrameExporter.beginHandLayer();
    }

    @Inject(method = "renderHand(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/Camera;F)V",
            at = @At("TAIL"))
    private void citiescraft$finishHandLayer(MatrixStack matrices, Camera camera, float tickDelta,
                                              CallbackInfo callbackInfo) {
        FrameExporter.finishHandLayer();
    }

    @Redirect(method = "render(FJZ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gl/Framebuffer;beginWrite(Z)V"))
    private void citiescraft$beginGuiLayer(Framebuffer framebuffer, boolean setViewport) {
        framebuffer.beginWrite(setViewport);
        if (framebuffer == MinecraftClient.getInstance().getFramebuffer()) FrameExporter.beginGuiLayer();
    }

    @Inject(method = "render(FJZ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;draw()V",
                    shift = At.Shift.AFTER))
    private void citiescraft$finishGuiLayer(float tickDelta, long startTime, boolean tick,
                                             CallbackInfo callbackInfo) {
        FrameExporter.finishGuiLayer(MinecraftClient.getInstance().getFramebuffer());
    }

    @Inject(method = "render(FJZ)V", at = @At("TAIL"))
    private void citiescraft$exportFinalFrame(float tickDelta, long startTime, boolean tick, CallbackInfo callbackInfo) {
        FrameExporter.afterRender(MinecraftClient.getInstance());
    }
}
