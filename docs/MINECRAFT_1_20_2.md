# Minecraft Java 1.20.2 toolchain

## Loader decision

Fabric is selected for the first client-only mod. Minecraft 1.20.2 requires Java 17. The exact 1.20.2 Fabric API branch uses Java 17 and provides client lifecycle/tick/render hooks and Mixin support. The current official Fabric example’s 1.20.2 branch is the source of truth for build-plugin syntax.

Forge also supports Java 17 and can reach the client renderer, but the inspected passthrough fork’s Forge code targets Minecraft 1.20.1 / Forge 47.4.10. Minecraft 1.20.2 needs the Forge 48.x line and a fresh check of every hook. No required mod dependency justifies that port for this prototype.

## Initial pins

Use the official Fabric API 1.20.2 branch as baseline: Java release 17, Minecraft 1.20.2, Fabric Loader 0.14.22, Yarn 1.20.2+build.1, Loom 1.4.1, Gradle wrapper 8.3, and Fabric API 0.91.6+1.20.2. These pins are recorded in the official branch's `gradle.properties`, `build.gradle` and wrapper properties. The wrapper scripts and JAR are taken from that same branch. Pin every resolved version; do not leave `SNAPSHOT` in a release build.

## Client hooks

| Need | 1.20.2 hook | Use |
| --- | --- | --- |
| Lifecycle | Fabric client entrypoint and client-stopping event | Start/stop the Bridge connection worker. |
| Player pose | `ClientTickEvents.END_CLIENT_TICK` | Send Minecraft player feet position and look state at a capped rate. |
| HUD | Fabric HUD render callback | Display latest Cities camera and Bridge status. |
| World rendering | `WorldRenderEvents` when an event suffices; Mixins only for capture boundaries absent from events | Keep renderer-sensitive work on the Minecraft render thread. |
| Camera | `GameRenderer#getCamera`, `Camera#getPos/getYaw/getPitch/getRotation` | Read/apply camera state after checking 1.20.2 timing. |
| Frame/depth | `GameRenderer`, `WorldRenderer` (Yarn name; Mojang mappings use `LevelRenderer`), `RenderTarget`, RenderSystem/LWJGL | Later capture world color/depth before hand and overlay after HUD/hand. |
| Native IPC | Loopback `Socket` for state; JNA/native helper for Windows shared memory | Never send frame pixels over TCP. |

Fabric API 1.20.2 `WorldRenderEvents` documents ordering and exposes render context. Prefer those events over brittle world-render Mixins when possible; still use an exact 1.20.2 Mixin if frame export requires a point outside the event API.

## Version-sensitive port checks

The inspected reference Forge source targets 1.20.1; the original Fabric implementation has also moved versions. Keep both outside the active source set. `GameRenderer`, `Camera`, framebuffer/render-target internals, OpenGL depth conversion and row orientation, mixin descriptors, player lifecycle, and entity methods must be checked against generated 1.20.2 sources.

A source comparison found the named Yarn signatures for `GameRenderer.renderWorld`, `getCamera`, `Camera` pose accessors, and `WorldRenderer.render` in both 1.20.1 and 1.20.2. This does not prove all injection points stayed stable: the WorldRenderer mixin internals do differ. Treat every injection as version-specific.

## References

- [Fabric 1.20.2 release note](https://fabricmc.net/2023/09/12/1202.html)
- [Fabric API source, 1.20.2 branch](https://github.com/FabricMC/fabric-api/tree/1.20.2)
- [ClientTickEvents source](https://github.com/FabricMC/fabric-api/blob/1.20.2/fabric-lifecycle-events-v1/src/client/java/net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents.java)
- [WorldRenderEvents source](https://github.com/FabricMC/fabric-api/blob/1.20.2/fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents.java)
- [Fabric example mod, 1.20.2 branch](https://github.com/FabricMC/fabric-example-mod/tree/1.20.2)
- [Yarn GameRenderer 1.20.2 Javadocs](https://maven.fabricmc.net/docs/yarn-1.20.2%2Bbuild.4/net/minecraft/client/render/GameRenderer.html)
- [Yarn WorldRenderer 1.20.2 Javadocs](https://maven.fabricmc.net/docs/yarn-1.20.2%2Bbuild.4/net/minecraft/client/render/WorldRenderer.html)
- [Forge Java 17 prerequisites](https://docs.minecraftforge.net/en/1.20.x/gettingstarted/)
- [Reference Forge 1.20.1 Gradle build](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/mc-forge/build.gradle)

## Verification status

Java 17.0.11 is installed. No Minecraft build or dev-client launch has been verified; the global Gradle installation fails during native initialization on this host.
