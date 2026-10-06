package dev.citiescraft.minecraft.mixin;

import java.util.ArrayList;
import java.util.List;

import dev.citiescraft.minecraft.CityCollisionWorld;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Adds streamed Cities surfaces and buildings to vanilla's existing movement collision solve. */
@Mixin(Entity.class)
abstract class EntityCollisionMixin {
    @Redirect(
            method = "adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getEntityCollisions(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Box;)Ljava/util/List;")
    )
    private List<VoxelShape> citiescraft$addRemoteCityCollision(World world, Entity entity, Box sweptBounds) {
        List<VoxelShape> vanilla = world.getEntityCollisions(entity, sweptBounds);
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerEntity localPlayer = client.player;
        if (localPlayer == null || !(entity instanceof PlayerEntity)
                || !localPlayer.getUuid().equals(entity.getUuid())) return vanilla;

        List<VoxelShape> city = CityCollisionWorld.collisions(sweptBounds);
        if (city.isEmpty()) return vanilla;
        ArrayList<VoxelShape> combined = new ArrayList<>(vanilla.size() + city.size());
        combined.addAll(vanilla);
        combined.addAll(city);
        return combined;
    }
}
