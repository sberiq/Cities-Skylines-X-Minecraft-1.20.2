# Building

## Toolchain

- Java 17 for the Bridge and Minecraft 1.20.2 client.
- Gradle 8.3 through the checked-in wrapper.
- Minecraft 1.20.2, Fabric Loader 0.14.22, Fabric API 0.91.6+1.20.2, Yarn 1.20.2+build.1 and Loom 1.4.1.
- Windows, the .NET Framework 3.5 targeting pack and Cities: Skylines 1 managed assemblies for the Cities mod.

The wrapper stores its distribution under Gradle's user home. Set `GRADLE_USER_HOME` to a writable cache location if the default home is unavailable. A first build downloads Gradle and Maven dependencies.

## Gradle modules

```sh
./gradlew build
./gradlew :bridge:run
./gradlew :minecraft:runClient
```

On Windows use `gradlew.bat` with the same task names. The development client uses the pinned Minecraft version in the Loom configuration.

On Windows, `build.ps1` runs the Gradle build. Pass `-Cities` to also compile the Cities mod after setting its managed assembly path.

## Cities mod

Build on Windows with the game installed and its managed assemblies available:

```powershell
$env:CS1_INSTALL = 'C:\Program Files (x86)\Steam\steamapps\common\Cities_Skylines'
msbuild cities\CitiesCraft.csproj /p:Configuration=Release
```

If the managed directory is elsewhere, set `CITIES_SKYLINES_MANAGED` directly to the directory containing `ICities.dll`, `Assembly-CSharp.dll`, `ColossalManaged.dll` and `UnityEngine.dll`. The output is `cities\bin\Release\CitiesCraft.dll`.

## Protocol smoke check

Compile and exercise the Bridge without Minecraft, Cities or Gradle:

```sh
mkdir -p bridge/build/smoke-classes
javac --release 17 -d bridge/build/smoke-classes bridge/src/main/java/dev/citiescraft/bridge/BridgeServer.java
python3 scripts/bridge-smoke.py
```

The smoke test binds a temporary loopback port. It verifies PLAYER and CAMERA routing, rejects non-finite values and prevents duplicate peers for a role.

## Current host verification

Java 17 Bridge build, Fabric mod build, and the Bridge protocol smoke check passed. The Minecraft development client launched and its log confirmed Minecraft 1.20.2, Fabric Loader 0.14.22 and `citiescraft 0.1.0` loaded. The current macOS host has no .NET SDK or Cities installation, so the Cities assembly cannot be built or loaded here.
