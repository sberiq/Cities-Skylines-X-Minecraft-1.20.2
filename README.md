# Cities × Minecraft 1.20.2

Passthrough prototype for a live Cities: Skylines city and Minecraft Java 1.20.2 running side by side.

## Current state

- Research, architecture and phase-one scaffolds are in place.
- The loopback Bridge compiles on Java 17; its process smoke check passed both directions, finite-number validation and duplicate-role rejection.
- The Fabric Minecraft 1.20.2 mod and Bridge build successfully. The Minecraft dev client launch is in progress; the Cities mod is scaffolded but has not been built or loaded.
- The end-to-end Phase 2 acceptance test remains open until real Minecraft 1.20.2 and Cities clients exchange continuously in game.
- No rendering, terrain, collision, coordinate calibration or depth integration is claimed yet.

## Run the Bridge

Java 17 is required. Start the local relay with `./gradlew :bridge:run`. It binds only to `127.0.0.1:25598`.

Build all Gradle modules with `./gradlew build`; launch the Minecraft development client with `./gradlew :minecraft:runClient`. See [build instructions](docs/BUILDING.md) and [installation status](docs/INSTALLATION.md).

See [the architecture notes](docs/ARCHITECTURE.md), [rendering plan](docs/RENDERING.md), [Minecraft toolchain](docs/MINECRAFT_1_20_2.md), and [coordinate model](docs/COORDINATES.md).
