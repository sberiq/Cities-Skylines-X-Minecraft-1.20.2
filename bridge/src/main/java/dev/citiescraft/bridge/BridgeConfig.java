package dev.citiescraft.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** Shared anchor, scale and orientation used by both game adapters. */
final class BridgeConfig {
    final WorldTransform transform;
    final float collisionRadius;

    private BridgeConfig(WorldTransform transform, float collisionRadius) {
        this.transform = transform;
        this.collisionRadius = collisionRadius;
    }

    static BridgeConfig load() {
        Properties properties = new Properties();
        String configuredPath = System.getProperty("citiescraft.config", "config/citiescraft.properties");
        Path path = Paths.get(configuredPath);
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
                System.out.println("Loaded world calibration from " + path.toAbsolutePath());
            } catch (IOException exception) {
                System.err.println("Could not read " + path + ": " + exception.getMessage());
            }
        } else {
            System.out.println("World calibration file not found at " + path.toAbsolutePath()
                    + "; using identity transform. See docs/COORDINATES.md.");
        }

        double scale = number(properties, "world.scale", 1.0);
        double yawDegrees = number(properties, "world.yaw-offset-degrees", 0.0);
        double mcX = number(properties, "world.minecraft-origin-x", 0.0);
        double mcY = number(properties, "world.minecraft-origin-y", 64.0);
        double mcZ = number(properties, "world.minecraft-origin-z", 0.0);
        double cityX = number(properties, "world.cities-origin-x", 0.0);
        double cityY = number(properties, "world.cities-origin-y", 0.0);
        double cityZ = number(properties, "world.cities-origin-z", 0.0);
        double radius = number(properties, "collision.radius-cities-units", 96.0);

        if (!Double.isFinite(scale) || scale <= 0.0 || scale > 64.0) {
            throw new IllegalArgumentException("world.scale must be finite and in (0, 64]");
        }
        if (!Double.isFinite(yawDegrees) || !finite(mcX, mcY, mcZ, cityX, cityY, cityZ)) {
            throw new IllegalArgumentException("world origins and yaw offset must be finite");
        }
        if (!Double.isFinite(radius) || radius < 16.0 || radius > 96.0) {
            throw new IllegalArgumentException("collision.radius-cities-units must be between 16 and 96");
        }

        return new BridgeConfig(new WorldTransform(scale, yawDegrees, mcX, mcY, mcZ, cityX, cityY, cityZ),
                (float) radius);
    }

    private static double number(Properties properties, String key, double fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid number for " + key + ": " + value, exception);
        }
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
