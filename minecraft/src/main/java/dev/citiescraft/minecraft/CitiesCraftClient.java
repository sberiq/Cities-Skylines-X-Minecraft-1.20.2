package dev.citiescraft.minecraft;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;

public final class CitiesCraftClient implements ClientModInitializer {
    private static final BridgeLink BRIDGE = new BridgeLink();
    private int tickCounter;
    private Object activeWorld;

    @Override
    public void onInitializeClient() {
        BRIDGE.start();
        FrameExporter.start();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            BRIDGE.stop();
            FrameExporter.stop();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
        HudRenderCallback.EVENT.register(this::renderHud);
    }

    private void tick(MinecraftClient client) {
        if (client.world != activeWorld) {
            activeWorld = client.world;
            CityCollisionWorld.clear();
        }
        if (client.player == null || client.world == null) return;
        if ((tickCounter++ % 3) != 0) return;
        Vec3d pos = client.player.getPos();
        BRIDGE.publishPlayer(pos.x, pos.y, pos.z, client.player.getYaw(), client.player.getPitch());
    }

    private void renderHud(DrawContext context, float tickDelta) {
        BridgeLink.CameraState camera = BRIDGE.cameraState();
        String status = BRIDGE.isConnected() ? "connected" : "waiting for Bridge";
        context.drawText(MinecraftClient.getInstance().textRenderer,
                "CitiesCraft: " + status, 8, 8, 0xFFFFFFFF, true);
        context.drawText(MinecraftClient.getInstance().textRenderer,
                "City collision: " + CityCollisionWorld.shapeCount() + " surfaces/boxes",
                8, 20, CityCollisionWorld.shapeCount() > 0 ? 0xFF8CFF8C : 0xFFFFD080, true);
        if (camera != null) {
            String line = String.format(java.util.Locale.ROOT,
                    "Cities camera %.1f, %.1f, %.1f | yaw %.1f pitch %.1f",
                    camera.x, camera.y, camera.z, camera.yaw, camera.pitch);
            context.drawText(MinecraftClient.getInstance().textRenderer, line, 8, 32, 0xFFB8E0FF, true);
        }
    }
}
