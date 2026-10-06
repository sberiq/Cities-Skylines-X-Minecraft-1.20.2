# Architecture

## Status and decisions

The target repository was empty. The architecture and scaffold are now checked into this workspace; this does not claim a completed game integration.

| Area | Decision | Basis |
| --- | --- | --- |
| Cities | Cities: Skylines 1 on Windows | Mature ICities / C# code-mod ecosystem, Unity Built-in render path, and community mods that locate/control the gameplay camera. |
| Minecraft | Java Edition 1.20.2 | Fixed requirement. |
| Loader | Fabric | Client tick/render events and Mixins fit the Minecraft client hooks; the upstream Fabric example has a 1.20.2 branch. |
| State IPC | Separate Bridge process over loopback TCP | Simple Java and legacy C# clients; used for low-rate telemetry only. |
| Final renderer | Cities | Unity can provide a camera depth texture and post-render effect in principle. Actual Cities effect injection remains a proof-of-concept gate. |
| Frame transfer | Windows shared-memory ring for the first render prototype | Compatible with the reference and avoids per-pixel TCP; rate/resolution must be limited. |

CS2 has a more modern official mod toolchain, but it uses ECS/Burst and HDRP, requiring a different custom render pass. It offers no advantage for the first Cities-host composite. Keep CS1 unless the live prototype proves its camera/depth path unworkable.

## Inspected repositories

- Target [sberiq/Cities-Skylines-X-Minecraft-1.20.2](https://github.com/sberiq/Cities-Skylines-X-Minecraft-1.20.2): empty repository on `main`.
- Reference [VortexisTV/wither-storm-gta5-passthrough](https://github.com/VortexisTV/wither-storm-gta5-passthrough): inspected source tree, README, license, Minecraft Forge 1.20.1 implementation, GTA ScriptHookV plugin, compositor, and shader.
- Origin [rehan-remade/universal-modder](https://github.com/rehan-remade/universal-modder/tree/main/examples/minecraft-gta5-passthrough): original Fabric/host/GTA source layout and attribution.
- CS1 camera examples: [FPSCamera](https://github.com/Asu4ni/CitiesSkylines-FPSCamera) and [PoliticsOverlay](https://github.com/shadijiha/Cities-Skyline-Politics-Mod/blob/main/Cities-Skyline-Politics-Mod/UI/PoliticsOverlay.cs). These are source examples, not guarantees for every game build.

## GTA reference findings

1. GTA is the rendering host. A GTA ScriptHookV plugin connects to the Minecraft mod’s loopback WebSocket (`127.0.0.1:25599`). GTA sends camera/player pose, input, and sampled ground. Minecraft reports game events back.
2. GTA native calls sample the active camera/player; `PlayerSync.java` applies the host pose to the Minecraft player/camera. In the normal mode GTA input drives Minecraft. An alternate flight path allows Minecraft elytra physics to drive the mapped GTA player.
3. The reference transform is GTA-specific: one GTA meter per block; GTA Z-up `(x,y,z)` maps to Minecraft `(x,z+yOffset,-y)`, yaw is `180° - heading`, and pitch is negated.
4. Minecraft’s Forge 1.20.1 `FrameExporter` captures world color/depth before hand rendering and a separate hand/HUD layer after render. OpenGL pixel-buffer objects and fences make readback asynchronous.
5. Java/JNA creates a Windows pagefile-backed mapping `Local\MCPassthroughFrame`. A versioned header and three slots carry sequence markers, dimensions, camera/FOV/clip metadata, RGBA color, float depth, and overlay color.
6. The GTA ReShade add-on copies those planes into Direct3D textures. Its shader linearizes GTA and Minecraft depth, reprojects rays between camera poses, composites the nearer surface, then draws the overlay. ReShade/GTA presents the final image.
7. GTA pedestrians/vehicles become invisible Minecraft proxy entities for mob targeting. GTA ground is sampled into tracked Minecraft collision columns; owned proxy blocks can be cleared safely.
8. GTA-specific input mapping, ScriptHookV natives, ReShade integration, world axes, ground probes, and native entity effects must be replaced. The reusable ideas are telemetry schemas, camera/frame metadata, ring-buffer sequencing, asynchronous readback, depth-aware compositing, and bounded collision/entity proxies.
9. The reference is not a Minecraft 1.20.2 implementation: its Forge module targets 1.20.1, while another Fabric branch has since moved to much newer game versions. No 1.20.1 source is assumed to compile on 1.20.2.

## Cities replacements

| GTA component | Cities: Skylines 1 equivalent | Risk |
| --- | --- | --- |
| ScriptHookV lifecycle/native tick | `ICities` loading/threading extension plus Unity `MonoBehaviour`; Harmony only for missing hooks | Check signatures against installed game assemblies. |
| GTA camera natives | Gameplay `CameraController` and its attached Unity `Camera`; `Camera.main` is not reliable in CS1 | Camera lookup/control is shown by public CS1 mods; verify on target build. |
| GTA Z-up conversion | Central `WorldTransform` in [COORDINATES.md](COORDINATES.md) | Scale and orientation need measured calibration. |
| ReShade depth/final image | Unity camera depth texture and an image effect in the Cities process | Most important unproven hook. |
| GTA ground sampling | Cities terrain height queries / raycasts, sampled around the player | Exact APIs and budget depend on installed game version. |
| GTA people/vehicle proxies | City IDs, transforms and coarse collision bounds; Minecraft-side proxies only when gameplay needs them | Data access and stable IDs require a Windows spike. |
| GTA socket/WebSocket | Loopback Bridge with one Minecraft and one Cities peer | State path first; frames use a separate channel. |
| GTA explosions, storm, vehicle forces | Later Cities simulation adapters for explicitly supported events | No direct one-to-one behavior for many effects. |

## Render host choice

**Selected: Cities owns the final render.** Cities renders its live city. Minecraft exports its world color/depth and later its HUD/hand plane. Cities composites those planes after the city scene and keeps its own output as the final image.

Cities: Skylines 1 is based on Unity 5.6 Built-in rendering. Unity documents camera depth textures and image effects for this pipeline. CS1 mods can run managed C# and public code mods access the gameplay camera. This makes Cities-host compositing plausible. No inspected mod proves our exact final-frame hook, depth coverage, resize behavior, or shader order, so these remain explicit acceptance gates.

**Alternative: Minecraft owns the final render.** This still requires Cities to export reliable color/depth. CS1’s older Unity version lacks asynchronous GPU readback, so synchronous readback can stall; Minecraft then adds projection/color/presentation conversion. Keep this as fallback only if the Cities-host image effect fails.

Stages: color-only composition; terrain depth; opaque building depth; selected props/agents where the live depth buffer supports them. A shader compiling is not proof of occlusion.

## First executable acceptance slice

- Bridge accepts one loopback client from each game.
- Minecraft sends player XYZ and Cities displays it in an in-game debug overlay.
- Cities sends camera XYZ and Minecraft displays it in its HUD.
- Both sides reconnect after Bridge restart and reject unsupported protocol versions.

The Java Bridge process and its loopback protocol smoke test are implemented. The Minecraft and Cities clients are scaffolded, but this acceptance slice has not yet been verified in either game. The automated smoke test proves protocol routing and validation only; it does not prove camera alignment, rendering, depth, collisions, or gameplay parity.

## Platform and license status

The current development host is macOS. It has Java 17.0.11, no .NET SDK, and its installed Gradle fails before project configuration with `Failed to load native library 'libnative-platform.dylib'`. Cities: Skylines 1 mod build and live-game acceptance require a Windows test environment.

The inspected passthrough source is MIT-licensed and credits Rehan / universal-modder contributors. This scaffold records its architecture without copying its application code. Any future adapted source must retain its original notices and be listed in `THIRD_PARTY_NOTICES.md`.

## References

- [Reference README](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/README.md)
- [GTA coordinates, camera, input, and ground sampling](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/gta/src/script.cpp)
- [GTA shared-frame reader and camera reprojection](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/gta/src/compositor.cpp)
- [GTA depth compositor](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/gta/shaders/MCPassthrough.fx)
- [Minecraft FrameExporter](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/mc-forge/src/main/java/dev/rehan/passthrough/client/FrameExporter.java)
- [Minecraft SharedMemory](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/mc-forge/src/main/java/dev/rehan/passthrough/client/SharedMemory.java)
- [Minecraft PlayerSync](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/mc-forge/src/main/java/dev/rehan/passthrough/client/PlayerSync.java)
- [Minecraft WorldBridge](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/mc-forge/src/main/java/dev/rehan/passthrough/WorldBridge.java)
- [Minecraft ProxyEntity](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/mc-forge/src/main/java/dev/rehan/passthrough/ProxyEntity.java)
- [Cities camera mod](https://github.com/Asu4ni/CitiesSkylines-FPSCamera)
- [Cities gameplay camera lookup](https://github.com/shadijiha/Cities-Skyline-Politics-Mod/blob/main/Cities-Skyline-Politics-Mod/UI/PoliticsOverlay.cs)
- [Paradox CS2 code-mod diary](https://www.paradoxinteractive.com/games/cities-skylines-ii/modding/dev-diary-3-code-modding)
- [Unity 5.6 depth mode](https://docs.unity3d.com/ja/560/ScriptReference/DepthTextureMode.html)
- [Unity depth-texture manual](https://docs.unity3d.com/es/530/Manual/SL-CameraDepthTexture.html)
