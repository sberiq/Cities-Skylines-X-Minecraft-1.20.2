# Architecture

## Selected design

| Part | Responsibility |
| --- | --- |
| Minecraft Java 1.20.2 with Fabric | Owns the player, inventory, blocks, world rendering and normal item interactions. Captures world color/depth, hand and HUD. |
| Cities: Skylines 1 Steam Mac mod | Owns the final screen, mapped camera and city scene. Sends input and city world snapshots; composites Minecraft with the city. |
| Local Java Bridge | Relays poses, settings, collision snapshots, input and CCF3 frame records over loopback TCP. |
| Calibration config | Maps one Minecraft player feet point to one Cities ground point, with shared scale and yaw. |

The user runs the games side by side on the same Mac. The Minecraft window can stay behind Cities; the mod disables pause-on-focus-loss so its world continues rendering and ticking. It temporarily disables view bobbing and dynamic FOV effects to align the exported perspective, and restores those settings when Minecraft closes. The Minecraft world is best kept void/empty so its landscape does not obscure the city. Cities remains the place the user sees and controls.

## Rendering and input path

1. The Minecraft camera view position, yaw, pitch, FOV and aspect are sent as `VIEW`; player feet are sent as `PLAYER`.
2. Cities maps `VIEW` to Unity units and follows it at render time on OpenGL (`-force-glcore` in the Steam launch options on macOS). It captures keyboard, mouse, wheel and text while Cities has focus.
3. Bridge forwards ordered `INPUT` records. Minecraft queues them and invokes its normal key, cursor and mouse callbacks on the client tick.
4. Minecraft exports 640×360 world color, linear depth, transparent hand, and transparent GUI layers using OpenGL PBOs.
5. CCF3 sends one atomic frame with its matching camera pose over a separate TCP connection. On OpenGL, Cities uploads the planes and a native macOS OpenGL image effect compares Minecraft depth against Cities camera depth. On Metal the effect is skipped; a Metal compositor is not implemented.
6. The nearer world pixel is drawn, followed by the Minecraft hand and HUD. Cities remains the final renderer.

If native shader setup fails, Cities logs the reason and leaves its regular image visible. A successful C# compile is not proof that native GL and Unity's callback work in the running game.

## Coordinates and collision

One configurable pair of origins links Minecraft XYZ to Cities XYZ. Y is up in both systems. `world.scale` converts blocks to Cities world units; `world.yaw-offset-degrees` handles horizontal orientation. Player feet align to the Cities anchor's sampled ground Y; the camera eye pose uses Minecraft's actual eye position.

Cities samples terrain and road/deck support on an 8-unit grid, and reports nearby static building bounds. The Bridge transforms these into Minecraft coordinates. A client mixin provides temporary movement-collision and crosshair/raycast shapes. They are approximations: they are not generated Minecraft blocks, cannot be mined, and do not edit the Cities save. Cars, citizens, detailed road walls, trees, props and tunnel floors lack full collision.

## Protocol

- State, settings, input and collision: UTF-8 records on `127.0.0.1:25598`.
- CCF3 image planes: versioned binary records on `127.0.0.1:25599`.
- Both ports bind to loopback only. Each game reconnects after Bridge restarts. The frame relay keeps only the latest fully received frame.

See [protocol layout](PROTOCOL.md), [coordinate calibration](COORDINATES.md), [render path and acceptance steps](RENDERING.md), and [installation](INSTALLATION.md).

## Status

The Bridge and Minecraft mod build with Java 17/Gradle, and the Cities DLL builds against the Steam Mac assemblies. A separate smoke script exercises coordinate and protocol paths, including four-layer frames. The user's Unity Player.log identifies the crash in `CitiesCraft.CitiesNativeCompositor.glCreateShader` while Metal was active. The updated mod skips the effect on Metal; Steam's `-force-glcore` option is needed to use the OpenGL compositor. That launch path and the visible HUD/hand, remote input, proxy placement, city ground alignment and collision fit still need live validation.
