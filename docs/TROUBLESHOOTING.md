# Troubleshooting

## Bridge does not start

- Confirm Java 17 is active with `java -version`.
- Confirm ports 25598 and 25599 are free.
- Start `./bin/bridge` from the extracted Bridge folder so it finds `config/citiescraft.properties`.
- Read the startup line to confirm the expected calibration file loaded. `-Dcitiescraft.config=/path/to/file` overrides its location.
- Both games and Bridge must run on the same Mac; both listeners bind only to `127.0.0.1`.

## Cities says Bridge disconnected

- Start Bridge before or after the games; the clients retry connections.
- Enable **CitiesCraft Passthrough** in the Steam game's Content Manager and load a save.
- Check the Bridge console for a `Connected: cities` line.

## Minecraft HUD says waiting for Bridge or collision count is zero

- Confirm the active Launcher installation is Minecraft **1.20.2**, Fabric Loader 0.14.22 or newer, and Fabric API `0.91.6+1.20.2`.
- Remove duplicate/older CitiesCraft JARs from that profile's `mods` directory.
- Keep a Cities save loaded; the city plugin sends the collision snapshot after Minecraft enters a world.
- Check the Bridge console for both `Connected: minecraft` and `Connected: cities`.
- If the Bridge restarted while Minecraft stayed open, wait several seconds for both state and frame links to reconnect.

## The player floats, sinks or misses the city

- Recheck the six origin values in `config/citiescraft.properties`. Minecraft F3 XYZ must be paired with the CitiesCraft overlay's Cities anchor X/ground-Y/Z.
- Change `world.yaw-offset-degrees` if forward movement maps along the wrong street direction.
- Start with `world.scale=1.0`; the collision grid uses 8-meter cells and can look stair-stepped.
- Restart Bridge after every config change, then re-enter the Minecraft world for a fresh snapshot.

## Minecraft inset is missing

- Keep the Bridge open and check its frame listener at port 25599.
- The Minecraft world must be open; the mod captures frames only while in a world.
- Replace the Minecraft JAR, Cities DLL and Bridge archive with the three files from the same dev kit, then reload the save/world.

## Build on macOS

The Cities build script prefers Steam's installed managed assemblies, then `/Applications/Cities.app`. If needed, set `CITIES_SKYLINES_MANAGED` to the folder containing `ICities.dll`. Install a .NET SDK (not only the runtime) before invoking `./scripts/build-cities-macos.sh`.
