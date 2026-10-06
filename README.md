# Cities × Minecraft 1.20.2

An incremental passthrough prototype for Cities: Skylines 1 and Minecraft Java 1.20.2 running side by side.

## Current build

- Minecraft captures its live 640×360 client frame, including the HUD, at up to 10 frames per second.
- The Bridge relays the latest frame over a separate loopback TCP channel.
- The Cities: Skylines mod displays the frame in a small picture-in-picture window, alongside the player and connection status.
- A shared configurable transform maps Minecraft XYZ/yaw into Cities coordinates and maps Cities camera/collision data back to Minecraft.
- Cities samples terrain and road/deck heights on an 8-meter grid and exports up to 128 nearby building bounds.
- A Minecraft 1.20.2 Mixin feeds those temporary collision shapes into vanilla movement physics; it does not edit Minecraft blocks or saves.
- All three components build on macOS against the Steam Cities assemblies. Protocol smoke checks cover calibrated coordinate round trips, collision geometry transforms, and frame transfer/reconnect.

The Minecraft frame is still shown as a 2D inset. Coordinates, sampled ground/road height and coarse static building collision are implemented, but need live testing and calibration in the user's save. Terrain is stair-stepped at 8-meter intervals; tunnel floors, moving vehicles/citizens, full 3D city rendering and depth occlusion are not implemented.

## Install the prototype

The complete locally built package is `dist/citiescraft-dev-kit.zip`. Extract it, then extract `bridge/bridge.zip` and edit `config/citiescraft.properties` inside the extracted Bridge folder using the [step-by-step macOS installation instructions](docs/INSTALLATION.md). Install the Minecraft JAR and Cities DLL from that same package.

## Build

Java 17 and Gradle 8.3 (through the checked-in wrapper) build the Bridge and Minecraft mod:

```sh
./gradlew build
```

On macOS, build the Cities mod against the game's installed managed assemblies:

```sh
./scripts/build-cities-macos.sh
```

See [build details](docs/BUILDING.md), [protocol](docs/PROTOCOL.md), [architecture notes](docs/ARCHITECTURE.md), [rendering plan](docs/RENDERING.md), [coordinate model](docs/COORDINATES.md), and [Minecraft 1.20.2 toolchain](docs/MINECRAFT_1_20_2.md).
