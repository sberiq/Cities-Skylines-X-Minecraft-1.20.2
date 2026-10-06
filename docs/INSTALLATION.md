# Install and use the passthrough on macOS

This prototype uses Cities: Skylines as the final 3D view. Minecraft supplies its world, Steve's hand, HUD, inventory and gameplay input. Cities supplies the surrounding scene and approximate collision surfaces. Keep Minecraft open in a separate window while Cities is in front.

## Package contents

- `minecraft/citiescraft-minecraft-0.1.0.jar` — Fabric client mod for Minecraft Java 1.20.2.
- `cities/CitiesCraft.dll` — Cities: Skylines 1 code mod for the Steam Mac build.
- `bridge/bridge.zip` — local state, input, calibration and four-layer image relay; includes its config file.

Install all three files from the same package. Remove older copies of CitiesCraft first.

## 1. Install both game mods

1. In the Minecraft Launcher, choose the Fabric profile for **Minecraft Java 1.20.2**. It needs Fabric Loader **0.14.22 or newer** and Fabric API `0.91.6+1.20.2`.
2. Open that profile's game directory and put `citiescraft-minecraft-0.1.0.jar` in its `mods` folder. The profile may use a folder other than `~/Library/Application Support/minecraft`.
3. In Finder, choose **Go → Go to Folder…** and open:

   ```text
   ~/Library/Application Support/Colossal Order/Cities_Skylines/Addons/Mods/
   ```

4. Create a `CitiesCraft` folder there and copy `CitiesCraft.dll` into it.
5. In Steam Library, open **Cities: Skylines → Properties → General → Launch Options** and enter `-force-glcore`. This makes Cities use OpenGL, which the compositor needs. Then start the **Steam** copy, enable **CitiesCraft Passthrough** in **Content Manager → Mods**, and load the city save you want to use.

## 2. Choose the Minecraft world and start point

Use a new **Creative Superflat world with the “The Void” preset**. The void matters because Cities will appear where Minecraft's world would normally be; Minecraft terrain would otherwise cover the city view. Keep the world and its save as a normal single-player world. This mod does not copy, convert or overwrite either game's save.

1. Start Minecraft 1.20.2 with the Fabric profile and create that world in Creative mode.
2. Stand at the Minecraft coordinate that you want to match to a point in your city. Press **F3** and write down the `XYZ` feet coordinates, including decimals and negative signs.
3. Keep Minecraft running. The mod disables pause-on-focus-loss so it continues to render and tick while Cities is the front window. It turns off view bobbing and FOV effects for camera alignment, then restores their previous values when Minecraft closes.

For a first run, use a flat, open Cities area and a clear Minecraft hotbar. The Minecraft capture is fixed at 640×360 (16:9); matching both games to a 16:9 window avoids a stretched view.

## 3. Read the Cities anchor

The anchor means: “the Minecraft F3 point will appear at this Cities ground point.” There is no automatic shared landmark between the two games, so you choose the pair.

1. In Cities, turn passthrough off with **F8** if the Minecraft image hides the CitiesCraft text.
2. Move the Cities camera target over the street or sidewalk where the Minecraft point should land. The CitiesCraft line shows `Cities anchor: X … ground Y … Z …`.
3. Write down all three values. Use the displayed **ground Y**, not the camera height. For the first setup leave scale and yaw at `1.0` and `0.0`.

F8 only switches the effect and input forwarding. It does not change the calibration values. With passthrough on, the captured Minecraft view covers the Cities camera image; the small CitiesCraft calibration text is hidden.

## 4. Enter the coordinate pair in Bridge

1. Extract `bridge/bridge.zip` somewhere easy to find, such as the Desktop. Open the extracted `bridge/config/citiescraft.properties` in TextEdit set to plain text or another text editor.
2. Copy the Minecraft F3 `XYZ` numbers to `world.minecraft-origin-x`, `world.minecraft-origin-y` and `world.minecraft-origin-z`.
3. Copy the CitiesCraft `X`, `ground Y` and `Z` numbers to the matching `world.cities-origin-*` lines.
4. Keep these initial values:

   ```properties
   world.scale=1.0
   world.yaw-offset-degrees=0.0
   ```

   Save the file. These are two matching points: at the saved Minecraft XYZ, the player feet should sit at the chosen Cities ground location.

Example values (replace these with your own):

```properties
world.minecraft-origin-x=42.5
world.minecraft-origin-y=71.0
world.minecraft-origin-z=-18.2
world.cities-origin-x=128.0
world.cities-origin-y=60.4
world.cities-origin-z=-35.0
```

Do not paste `XYZ:` or commas; each setting takes one number. Keep all six values from the same chosen pair.

## 5. Launch and connect the three parts

1. Open Terminal. Type `cd ` with a trailing space, drag the extracted `bridge` folder into Terminal, and press Return.
2. Start Bridge with:

   ```sh
   ./bin/bridge
   ```

   Leave that Terminal window open. Java 17 must be installed. The Bridge listens on `127.0.0.1:25598` for state/input and `127.0.0.1:25599` for Minecraft image layers.
3. Load your Cities save and your Minecraft void world. Wait until Minecraft's HUD says it is connected and the city collision count rises above zero.
4. Click the Cities window so it is in front. Press **F8** to turn passthrough on if needed. Cities then follows Minecraft's mapped eye position and view direction. Keyboard, mouse, scroll, hotbar and inventory input are sent to Minecraft.
5. Move with **W/A/S/D**, look with the mouse, jump with **Space**, open inventory with **E**, use the hotbar number keys, and place blocks with right-click. **F5** changes Minecraft's camera view if you want to see Steve.

Restart Bridge after changing its config. The game mods reconnect automatically. If you change Minecraft's origin after the player has moved, return to that XYZ or update the origin pair together.

## 6. Check the coordinate alignment

1. Start with the Minecraft player standing at the recorded origin.
2. Look at the Cities street point used for the anchor. Player feet should meet its surface. If the player floats or sinks, adjust `world.cities-origin-y` by the visible difference and restart Bridge.
3. Walk forward briefly. If movement follows the wrong street direction, adjust `world.yaw-offset-degrees` in 90-degree steps first, then fine-tune it. Restart Bridge after each change.
4. If the mapped position is too large or small relative to buildings, adjust `world.scale` a little around `1.0`. This changes position and city collision dimensions together.

## What this prototype supports

Cities renders its live 3D scene. Minecraft's world image is composited into the same camera view using depth, with the first-person hand and GUI on top. The city terrain and nearby static buildings are sent to Minecraft as temporary collision and targeting proxies. Terrain samples are spaced 8 Cities units apart; building bounds are approximate boxes.

City surfaces are not Minecraft blocks. You cannot mine a road or building as a Minecraft block, and city damage/building changes are not implemented. Right-click block placement against a city proxy is wired through Minecraft's interaction path but still needs an in-game check. Minecraft blocks are placed and saved in the Minecraft world; a void preset keeps hidden Minecraft terrain from competing with Cities. Cars, citizens, trees, props and tunnel floors do not yet have full collision geometry. Depth occlusion, texture orientation, camera alignment, F8 handling and gameplay input have not been tested by launching both games on this build host.

If the overlay says `passthrough unavailable: Metal`, Steam's launch option did not switch this run to OpenGL. Close Cities, check that `-force-glcore` is still in Launch Options, and start it again. The image effect stays disabled on Metal to prevent the reported crash. On OpenGL, if the effect falls back to a Cities-only image, check the Bridge and Cities logs as described in [troubleshooting](TROUBLESHOOTING.md). Keep the same game builds and read [coordinate details](COORDINATES.md) before tuning scale or yaw.
