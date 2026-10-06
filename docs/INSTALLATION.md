# Installation status

This is an early telemetry prototype, not the finished passthrough. The Minecraft 1.20.2 JAR and Bridge are built, and the Minecraft dev log confirms the mod loads. The Cities mod is source only and must be compiled on Windows against the installed CS1 managed assemblies. The Bridge smoke test is verified; the two real games have not yet exchanged state together.

## Install the Minecraft mod

1. Use Minecraft Java Edition 1.20.2 with Fabric Loader 0.14.22.
2. Install Fabric API 0.91.6+1.20.2 in the same profile.
3. Copy `citiescraft-minecraft-0.1.0.jar` into that profile's `mods` folder. The dev kit contains this JAR at `minecraft/citiescraft-minecraft-0.1.0.jar`.

## Build and install the Cities mod (Cities: Skylines 1)

This mod targets CS1 only. On Windows, open a Visual Studio Developer PowerShell and set the managed assembly folder to your game installation:

```powershell
$env:CITIES_SKYLINES_MANAGED = 'C:\Program Files (x86)\Steam\steamapps\common\Cities_Skylines\Cities_Data\Managed'
msbuild .\cities\CitiesCraft.csproj /p:Configuration=Release
```

Copy `cities\bin\Release\CitiesCraft.dll` to `%LOCALAPPDATA%\Colossal Order\Cities_Skylines\Addons\Mods\CitiesCraft\CitiesCraft.dll`, enable **CitiesCraft Passthrough** in the game's Content Manager, and load your save. If MSBuild reports a missing assembly, point `CITIES_SKYLINES_MANAGED` at the directory containing `ICities.dll`, `Assembly-CSharp.dll`, `ColossalManaged.dll` and `UnityEngine.dll`.

## Start both clients

1. Extract `bridge/bridge.zip` from the dev kit.
2. Run `bridge\bin\bridge.bat` on the same computer as both games. Java 17 must be on `PATH`.
3. Load your existing CS1 save and open a world in Minecraft 1.20.2 with the Fabric mod.
4. Look for Minecraft player XYZ in the Cities debug overlay and the Cities camera XYZ in the Minecraft HUD.

This build synchronizes telemetry only. It does not yet draw Minecraft inside the city, move Steve onto Cities terrain, or add collision/occlusion. These steps validate the first live game-to-game link; they are not the finished passthrough experience.
