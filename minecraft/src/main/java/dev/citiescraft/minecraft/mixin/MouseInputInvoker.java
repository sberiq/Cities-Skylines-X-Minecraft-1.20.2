package dev.citiescraft.minecraft.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Access to Minecraft's normal mouse callbacks for input relayed from the Cities host. */
@Mixin(Mouse.class)
public interface MouseInputInvoker {
    @Invoker("onMouseButton")
    void citiescraft$onMouseButton(long window, int button, int action, int modifiers);

    @Invoker("onCursorPos")
    void citiescraft$onCursorPos(long window, double x, double y);

    @Invoker("onMouseScroll")
    void citiescraft$onMouseScroll(long window, double horizontal, double vertical);
}
