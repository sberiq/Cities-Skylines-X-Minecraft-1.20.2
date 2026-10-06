# Installation status

This is an early telemetry prototype, not the finished passthrough. The Minecraft 1.20.2 JAR, Cities: Skylines 1 DLL and Bridge are built. The Minecraft dev log confirms that mod loads. The Cities DLL compiled on macOS against the installed CS1 managed assemblies. The Bridge smoke test is verified; the two real games have not yet exchanged state together.

## Install the Minecraft mod

1. Use Minecraft Java Edition 1.20.2 with Fabric Loader 0.14.22.
2. Install Fabric API 0.91.6+1.20.2 in the same profile.
3. Copy `citiescraft-minecraft-0.1.0.jar` into that profile's `mods` folder. The dev kit contains this JAR at `minecraft/citiescraft-minecraft-0.1.0.jar`.

## Build and install the Cities mod (Cities: Skylines 1)

This mod targets CS1 only. On macOS, install a .NET SDK and run:

```sh
./scripts/build-cities-macos.sh
```

The dev kit includes `cities/CitiesCraft.dll`. To rebuild it, install a .NET SDK and run `./scripts/build-cities-macos.sh`; the script finds the game's managed assemblies automatically at `/Applications/Cities.app` or its standard Steam location. Set `CITIES_SKYLINES_MANAGED` if your installation is elsewhere. Copy `cities/CitiesCraft.dll` (or the rebuilt `cities/bin/Release/CitiesCraft.dll`) to `~/Library/Application Support/Colossal Order/Cities_Skylines/Addons/Mods/CitiesCraft/CitiesCraft.dll`, enable **CitiesCraft Passthrough** in the game's Content Manager, and load your save.

On Windows, build `cities/CitiesCraft.csproj` with MSBuild and set `CITIES_SKYLINES_MANAGED` to the game's `Managed` folder.

## Start both clients

1. Extract `bridge/bridge.zip` from the dev kit.
2. Run `bridge/bin/bridge` on macOS or `bridge\bin\bridge.bat` on Windows. Java 17 must be on `PATH`.
3. Load your existing CS1 save and open a world in Minecraft 1.20.2 with the Fabric mod.
4. Look for Minecraft player XYZ in the Cities debug overlay and the Cities camera XYZ in the Minecraft HUD.

This build synchronizes telemetry only. It does not yet draw Minecraft inside the city, move Steve onto Cities terrain, or add collision/occlusion. These steps validate the first live game-to-game link; they are not the finished passthrough experience.
