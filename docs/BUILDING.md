# Building

## Toolchain

- Java 17 for the Bridge and Minecraft 1.20.2 client.
- Gradle 8.3 through the checked-in wrapper.
- Minecraft 1.20.2, Fabric Loader 0.14.22, Fabric API 0.91.6+1.20.2, Yarn 1.20.2+build.1 and Loom 1.4.1.
- A .NET SDK for the macOS Roslyn build script, plus the Cities: Skylines 1 managed assemblies. The game's own old Mono/Unity assemblies are the compile references.

The wrapper stores its distribution under Gradle's user home. Set `GRADLE_USER_HOME` to a writable cache location if the default home is unavailable. A first build downloads Gradle and Maven dependencies.

## Gradle modules

```sh
./gradlew build
./gradlew :bridge:run
./gradlew :minecraft:runClient
```

On Windows use `gradlew.bat` with the same task names. The development client uses the pinned Minecraft version in the Loom configuration.

On Windows, `build.ps1` runs the Gradle build. Pass `-Cities` to also compile the Cities mod after setting its managed assembly path.

## Cities mod on macOS

Cities: Skylines 1 and its `Managed` assemblies are present on the current Mac. Install a .NET SDK (the runtime alone does not include the compiler), then run:

```sh
./scripts/build-cities-macos.sh
```

The script prefers the standard Steam app location, then checks `/Applications/Cities.app/Contents/Resources/Data/Managed`. Set `CITIES_SKYLINES_MANAGED` to override the path. It invokes the SDK's Roslyn compiler against the game's Mono framework references and outputs `cities/bin/Release/CitiesCraft.dll`.

## Cities mod on Windows

The Windows project can be built with the game installed and its managed assemblies available:

```powershell
$env:CS1_INSTALL = 'C:\Program Files (x86)\Steam\steamapps\common\Cities_Skylines'
msbuild cities\CitiesCraft.csproj /p:Configuration=Release
```

If the managed directory is elsewhere, set `CITIES_SKYLINES_MANAGED` directly to the directory containing `ICities.dll`, `Assembly-CSharp.dll`, `ColossalManaged.dll` and `UnityEngine.dll`. The output is `cities\bin\Release\CitiesCraft.dll`.

## Assemble the macOS dev kit

After building the Java modules and Cities DLL, run:

```sh
./scripts/package-macos.sh
```

The script creates `dist/citiescraft-dev-kit.zip` with the Minecraft JAR, Cities DLL, Bridge distribution and matching docs. It stops if any component has not been built.

## Bridge protocol smoke check

Compile the Bridge with Gradle, then exercise its loopback protocol without launching either game:

```sh
./gradlew :bridge:classes
python3 scripts/bridge-smoke.py
```

The smoke check binds temporary loopback ports. It verifies a nonzero scale/yaw/origin mapping, camera and input routing, terrain and building proxy conversion, CCF3's four image layers with a matched camera pose, and frame reconnect. It does not launch either game.

## Current host verification

The build host has Java 17 and the Steam Cities managed assemblies. Gradle 8.3 builds the Bridge and Minecraft mod; the macOS script compiles the Cities DLL. The process-level Bridge smoke check covers the state and frame channels. The games still need a user-side live-save check for coordinate calibration, collisions, camera input and the full-screen depth composite; do not launch the development client when building distribution artifacts.
