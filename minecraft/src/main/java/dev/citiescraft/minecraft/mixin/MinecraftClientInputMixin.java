package dev.citiescraft.minecraft.mixin;

import dev.citiescraft.minecraft.RemoteInputState;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drains relayed host input on Minecraft's own client thread. */
@Mixin(MinecraftClient.class)
abstract class MinecraftClientInputMixin {
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void citiescraft$drainRemoteInput(CallbackInfo callbackInfo) {
        RemoteInputState.drain((MinecraftClient) (Object) this);
    }
}
