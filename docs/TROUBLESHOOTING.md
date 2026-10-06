# Troubleshooting

## Cities shows its normal camera image

- Confirm both mods and Bridge came from the same dev kit.
- In Minecraft, enter a world and look for `CitiesCraft: connected`. In Bridge's Terminal window, check that both game roles connected.
- Read the CitiesCraft status line. If it says `passthrough unavailable: Metal`, close Cities and add `-force-glcore` under Steam Library → Cities: Skylines → Properties → General → Launch Options, then restart the game. The effect stays disabled on Metal to avoid the crash in `glCreateShader` from the camera callback.
- On a supported OpenGL renderer, press **F8** in Cities to enable passthrough. The game view and frame must be fresh. If it still shows Cities only, check the Bridge frame listener on `127.0.0.1:25599` and the Cities log for `native compositor unavailable`.
- The current compositor requires the Cities gameplay camera's image-effect callback and OpenGL 3.2 shader support. The Unity Player.log should report OpenGL for `Gfx Version`; if it still reports Metal, the launch option did not take effect. On initialization failure the mod keeps the Cities frame visible and writes the reason to the game log.
- Use 16:9 windows for both games while testing; the Minecraft capture has fixed 640×360 output.

## Cities view appears but the camera does not follow Minecraft

- Keep Minecraft open in a loaded world. Its HUD should show `connected`.
- Click Cities so it is the front window; Cities must be able to read keyboard and mouse input for forwarding.
- Check the Bridge console for both state connections. Restart Bridge after editing the config.
- If the view is stale, the mod releases pressed input and gives control back to the Cities camera. Reconnect by waiting for the Minecraft frame and view to return, then press F8 if passthrough is off.
- Minecraft pause-on-focus-loss is disabled by the mod so rendering and game ticks continue while Cities has focus.

## Minecraft shows “waiting for Bridge” or collision count is zero

- Confirm the running profile is Minecraft Java **1.20.2**, Fabric Loader is installed, and Fabric API `0.91.6+1.20.2` is present.
- Remove duplicate or older CitiesCraft JARs from that profile's `mods` directory.
- Load a Cities save. Cities sends collision snapshots after Minecraft enters a world and player location is available.
- Check that Bridge reports both `minecraft` and `cities` connected. Bridge binds only to `127.0.0.1` on ports 25598 and 25599.
- Wait a few seconds after restarting Bridge; both clients retry automatically.

## Player is floating, sinking, or moving in the wrong direction

- Recheck all six origin values. Minecraft's three values come from F3 `XYZ`; Cities' three come from the overlay's anchor and **ground Y**.
- Height problems are corrected with `world.cities-origin-y`. Direction problems are corrected with `world.yaw-offset-degrees`; test 90-degree changes before fine adjustments.
- Keep scale at `1.0` until height and direction make sense. Change scale only if the same route appears too large or too small.
- Restart Bridge after config changes. Keep the Minecraft player at the configured Minecraft origin while checking the mapped starting point.
- City terrain proxies are 8-unit columns and building proxies are approximate boxes, so edges and slopes can feel coarse.

## Crosshair targets city surfaces but placement fails

City roads and buildings are not Minecraft blocks. The mod creates temporary collision and raycast shapes. It can route a crosshair hit into Minecraft's normal item-use path, but placement against an invisible city surface still needs a live test. Mining or changing Cities assets is not implemented.

## Minecraft layer is blank, upside down, or translucent

- Check the Bridge log and Cities log for an invalid CCF3 header or native compositor error.
- Use all three artifacts from one dev kit; the old CCF2 frame protocol is not compatible.
- Check that Minecraft has finished loading into a world and that the capture status/frame counter advances.
- If alpha or depth looks wrong, save the Cities log with the affected screenshot. Texture orientation and depth conventions need testing across the user's GPU and game versions.

## Bridge does not start

- Confirm Java 17 is active with `java -version`.
- Start `./bin/bridge` from the extracted Bridge folder so it finds `config/citiescraft.properties`.
- Confirm ports 25598 and 25599 are free. Both listeners bind only to loopback on this Mac.
- Read the startup log to confirm the config path. `-Dcitiescraft.config=/path/to/file` overrides the config location.

## Build the Cities mod on macOS

The script prefers Steam's installed Managed assemblies, then `/Applications/Cities.app`. Set `CITIES_SKYLINES_MANAGED` to the folder containing `ICities.dll` if needed. Install a .NET SDK, not only the runtime, before invoking `./scripts/build-cities-macos.sh`.
