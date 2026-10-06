# Cities × Minecraft 1.20.2 passthrough

This macOS prototype makes Cities: Skylines 1 the visible game. Minecraft supplies the first-person world, hand, HUD, inventory and input. Cities follows Minecraft's eye camera and composites Minecraft pixels only where their depth is in front of the city. The Bridge carries camera state, input and frame layers over localhost.

## Current implementation

- Minecraft 1.20.2 Fabric client captures a 640×360 world image, linear depth, first-person hand and HUD/screen as synchronized layers at up to 20 fps.
- The Java Bridge relays CCF3 layers, including the exact camera pose captured with each image, and maps coordinates between the games.
- Cities: Skylines 1 follows the mapped Minecraft camera and runs an OpenGL 3.2 compositor against the city's depth texture. On macOS, start Cities with the Steam launch option `-force-glcore` so its renderer matches this compositor.
- Keyboard, text, mouse look, clicks and scroll are forwarded from Cities to Minecraft. Press **F8** in Cities to suspend or resume passthrough and input forwarding.
- Nearby terrain/road and static-building bounds feed Minecraft movement collision. A synthetic crosshair ray can target streamed city surfaces; ordinary Minecraft air cells are required for block placement.

## Limits to know

The crash log and Cities' Unity Player.log pinpointed the fault: `CitiesCraft.CitiesNativeCompositor.glCreateShader` was called while the game used Metal. The updated mod skips its image effect on Metal rather than crashing. Use Steam's `-force-glcore` launch option to run the OpenGL compositor; if the game still reports Metal, passthrough remains unavailable. This setup has not yet been confirmed in a successful live session. Camera alignment, mouse feel and occlusion still need live validation. Capture is fixed at 640×360/20 fps. Minecraft world post-effects are not included, transparent-world depth and overlapping HUD alpha can be imperfect, and Cities vehicles/props may not provide useful depth.

Use a separate Minecraft **The Void** Superflat world while testing. Normal Minecraft terrain is still rendered from the Minecraft world, and its hidden blocks can interfere with city placement. The mod temporarily turns off view bobbing and FOV effects so the rendered camera stays matched with Cities, then restores your settings when Minecraft closes. City buildings are collision and raycast proxies, not real Minecraft blocks: they cannot be mined, and reliable placement against them still needs in-game validation. Any blocks you place are saved in the Minecraft world.

## Build and install

Build instructions: [docs/BUILDING.md](docs/BUILDING.md). Install, align the two coordinate origins, and run the games: [docs/INSTALLATION.md](docs/INSTALLATION.md).

The build output is `dist/citiescraft-dev-kit.zip`, assembled with `./scripts/package-macos.sh` after the three components build. Use the Minecraft JAR, Cities DLL and Bridge from the same archive.
