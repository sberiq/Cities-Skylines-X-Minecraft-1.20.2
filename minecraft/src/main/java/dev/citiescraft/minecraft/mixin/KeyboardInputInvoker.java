package dev.citiescraft.minecraft.mixin;

import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Access to Minecraft's normal text callback for input relayed from the Cities host. */
@Mixin(Keyboard.class)
public interface KeyboardInputInvoker {
    @Invoker("onChar")
    void citiescraft$onChar(long window, int codepoint, int modifiers);
}
