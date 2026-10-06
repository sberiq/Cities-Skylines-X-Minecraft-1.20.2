# Porting to Minecraft 1.20.2

The inspected passthrough variants target Forge 1.20.1 and a much newer Fabric release. They are references only.

| Reference | Minecraft 1.20.2 check |
| --- | --- |
| Forge 47.4.10 / Minecraft 1.20.1 | Not active in this repo; if Forge is reconsidered, use its 1.20.2 / 48.x dependency line. |
| Official Mojang mappings in Forge | Selected Fabric scaffold uses pinned Yarn or official mappings; keep all source and Mixin names consistent with that choice. |
| GameRenderer / Camera | Compare exact 1.20.2 generated source and descriptors. Verify pose timing, first/third-person collision, FOV and view bobbing. |
| LevelRenderer / WorldRenderer | Mojang mappings call this `LevelRenderer`; Yarn calls it `WorldRenderer`. Confirm injection point, not just method name. |
| RenderTarget / framebuffer | Verify attachments, resize, depth format, GL state, capture point and render-thread requirement. |
| OpenGL readback | Verify depth range, reversed-Z flag if present, pixel-pack-buffer support, fences, row order and pack alignment. |
| Player/entity/world APIs | Check respawn, world unload, client-only class loading, hit/block methods and interpolation. |
| Mixins/events | Prefer 1.20.2 Fabric API events where available; validate every remaining target at startup. |
| IPC | Use this project’s loopback protocol, independent of Minecraft multiplayer networking. |

Yarn docs show `GameRenderer.renderWorld`, camera accessors, and `WorldRenderer.render` signatures in both 1.20.1 and 1.20.2. That is limited evidence only: 1.20.2 Fabric API render hooks and mixin internals can differ. The reference’s 1.20.1 Mixins are not copied into this client.

Port gate: build and launch a bare 1.20.2 client first, then add lifecycle/tick/HUD hooks, then add each renderer hook individually with a recorded descriptor and smoke check. No rendering code is currently claimed as ported.
