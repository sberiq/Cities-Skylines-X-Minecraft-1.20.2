# Install and calibrate on macOS

This build sends Minecraft's live image to Cities as a small inset. It also maps the Minecraft player's position into Cities, samples ground and road/deck heights, and adds nearby static building bounds to Minecraft movement collisions.

## Files in the dev kit

- `minecraft/citiescraft-minecraft-0.1.0.jar` — Fabric mod for Minecraft Java 1.20.2.
- `cities/CitiesCraft.dll` — Cities: Skylines 1 mod built against the Steam Mac assemblies.
- `bridge/bridge.zip` — state, coordinate and image relay; includes `config/citiescraft.properties`.

Use all three from the same dev kit. Remove an older `citiescraft-minecraft-0.1.0.jar` from the selected profile before copying in the new one.

## 1. Install the Minecraft mod

1. In Minecraft Launcher, select the Fabric profile for Java Edition **1.20.2**.
2. Confirm the profile has Fabric Loader 0.14.22 or newer and Fabric API `0.91.6+1.20.2`.
3. Open that launcher's game directory, then its `mods` folder (create `mods` if it is missing).
4. Copy `minecraft/citiescraft-minecraft-0.1.0.jar` from the dev kit into `mods`.

The profile's directory may differ from `~/Library/Application Support/minecraft`; use the folder configured for that Fabric installation.

## 2. Install the Cities mod

1. In Finder, choose **Go → Go to Folder…** and enter:

   ```text
   ~/Library/Application Support/Colossal Order/Cities_Skylines/Addons/Mods/
   ```

2. Create a folder named `CitiesCraft` if needed.
3. Copy the new `cities/CitiesCraft.dll` into that folder, replacing the previous DLL.
4. Start the **Steam** copy of Cities: Skylines 1, enable **CitiesCraft Passthrough** under **Content Manager → Mods**, and load your save.

The CitiesCraft overlay's **Cities anchor** line shows the camera target X/Z and sampled ground Y. Pan the camera until the target is over the street or sidewalk where the Minecraft player should start. This also gives you the Cities coordinates for calibration.

## 3. Read the Minecraft anchor

1. Start Minecraft Java **1.20.2** with the Fabric profile installed above.
2. Enter the world and stand at the Minecraft location that should map to the Cities anchor. Use **F3** and record the player's `XYZ`. If Bridge is not running yet, the Minecraft HUD can say it is waiting for Bridge.
3. Keep the three Minecraft values handy. These become the Minecraft origin coordinates.

The two games have no shared landmark, so you choose which Minecraft point corresponds to the selected Cities street point. The world scale starts at one Cities unit per Minecraft block; adjust yaw/scale only after this first mapping is working.

## 4. Set the shared origins in Bridge

1. Extract `bridge/bridge.zip` from the dev kit. The extracted folder contains `bin/bridge` and `config/citiescraft.properties`.
2. Open `config/citiescraft.properties` in a plain-text editor.
3. Enter the F3 values in `world.minecraft-origin-x/y/z` and the Cities overlay values in `world.cities-origin-x/y/z`. Keep `world.scale=1.0` and `world.yaw-offset-degrees=0.0` for the initial check. The Cities Y value is the **ground Y** shown by the overlay.
4. Save the file. The Bridge must be restarted after changing calibration.

Example: if F3 reads `XYZ: 42.5 / 71.0 / -18.2` and the selected Cities street anchor reads `X 128.0 / ground Y 60.4 / Z -35.0`, use:

```properties
world.minecraft-origin-x=42.5
world.minecraft-origin-y=71.0
world.minecraft-origin-z=-18.2
world.cities-origin-x=128.0
world.cities-origin-y=60.4
world.cities-origin-z=-35.0
```

The origin values are paired anchors: standing at the Minecraft origin maps to the Cities anchor. `collision.radius-cities-units` controls the bounded city query radius from 16 to 96 meters.

## 5. Run Bridge and both games

1. Open Terminal, type `cd ` (with a trailing space), drag the extracted `bridge` folder into Terminal, then press Return.
2. Run `./bin/bridge`. Leave that Terminal window open. It should confirm the config path and listeners `127.0.0.1:25598` (state) and `127.0.0.1:25599` (frames). Java 17 must be on `PATH`.
3. Load your Steam Cities save, then open a Minecraft 1.20.2 world in the Fabric profile.
4. In Cities, check for `Bridge connected`, mapped Minecraft XYZ and `Minecraft frame #...` in the lower-right image.
5. In Minecraft, check the HUD's `City collision` count. A fresh Cities snapshot should provide terrain columns plus nearby building boxes. Walk over a street and into a nearby building to check floor and wall collisions.

If Minecraft was already open while you edited calibration, close and re-enter its world after restarting Bridge. Bridge and both game mods reconnect on their own.

## What works and what still needs a live check

Minecraft keeps vanilla movement and Minecraft blocks. Cities terrain/road heights arrive as 8-meter collision columns; nearby static buildings arrive as approximate axis-aligned boxes. They are temporary collision shapes, not placed blocks, so the Minecraft save is not rewritten. The live Minecraft image, including HUD, appears in the Cities inset at up to 10 fps and 640×360.

This prototype does not yet render Cities as a full-screen 3D background, apply depth occlusion, collide with moving cars/citizens/props, or provide tunnel floors. The 8-meter height grid can feel stair-stepped, and yaw/scale/ground alignment must be verified in your particular city save. No live game was launched by the build process.

To rebuild on this Mac, install a .NET SDK and run `./scripts/build-cities-macos.sh`. The script prefers Steam's managed assemblies; set `CITIES_SKYLINES_MANAGED` to override the location.
