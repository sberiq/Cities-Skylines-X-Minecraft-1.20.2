package dev.citiescraft.minecraft.mixin;

import dev.citiescraft.minecraft.CitySurfaceRaycaster;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes streamed Cities proxies participate in the normal Minecraft crosshair target. */
@Mixin(GameRenderer.class)
abstract class CityCrosshairMixin {
    @Inject(method = "updateTargetedEntity(F)V", at = @At("TAIL"))
    private void citiescraft$targetCitySurface(float tickDelta, CallbackInfo callbackInfo) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.interactionManager == null) return;

        Camera camera = client.gameRenderer.getCamera();
        Vec3d start = camera.getPos();
        Vec3d direction = client.player.getRotationVec(tickDelta).normalize();
        double reach = client.interactionManager.getReachDistance();
        Vec3d end = start.add(direction.multiply(reach));
        BlockHitResult cityHit = CitySurfaceRaycaster.raycast(start, end);
        if (cityHit == null) return;

        BlockPos placementCell = cityHit.getBlockPos();
        BlockPos adjacentPlacementCell = placementCell.offset(cityHit.getSide());
        // The hit block and vanilla's adjacent placement destination must both be air
        // and within the build range. This prevents a proxy hit overriding a real block.
        if (client.world.isOutOfHeightLimit(placementCell)
                || client.world.isOutOfHeightLimit(adjacentPlacementCell)
                || !client.world.getBlockState(placementCell).isAir()
                || !client.world.getBlockState(adjacentPlacementCell).isAir()) return;

        HitResult current = client.crosshairTarget;
        if (current == null || current.getType() == HitResult.Type.MISS
                || start.squaredDistanceTo(cityHit.getPos()) < start.squaredDistanceTo(current.getPos())) {
            client.crosshairTarget = cityHit;
        }
    }
}
