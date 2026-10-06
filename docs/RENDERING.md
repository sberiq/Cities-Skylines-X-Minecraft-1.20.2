# Rendering architecture

## Chosen host: Cities

Cities: Skylines renders its real live city and owns the final presented frame. Minecraft exports its real world layer and depth; Cities composites those planes using its own camera/depth. Minecraft remains a separate running game and owns Minecraft gameplay.

CS1 uses Unity 5.6 Built-in rendering. Unity documents camera depth textures and image effects for this pipeline. Existing CS1 camera mods reach the gameplay camera and can change FOV/near plane. This makes the Cities-host path plausible, but the exact final-frame callback, depth coverage, color ordering and resize behavior are still untested.

## Alternative: Minecraft host

Minecraft owns the final view after Cities exports its final color and depth. Minecraft has direct OpenGL access, but the hard Unity capture step remains. CS1’s older Unity version lacks asynchronous GPU readback, so synchronous readback can stall; moving output to Minecraft also adds projection, tone and presentation conversion. Keep it as a fallback if the Cities image-effect path fails.

## Stages

1. Color-only composite, exported at reduced size/rate.
2. City terrain depth and Minecraft depth in a common linear convention.
3. Opaque building depth, with one behind/front marker test.
4. Props and selected agents only if live depth includes them.

A full HD RGBA8 plane is about 8.3 MB/frame; three color/depth/overlay planes at 60 fps can approach 1.5 GB/s before extra copies. Use async Minecraft readback, triple buffering, drop stale frames, and profile before raising resolution. Unity 5.6 readback must be measured separately.

## Acceptance

Show both live games together; place a marker behind terrain and behind an opaque building; confirm each is hidden; place another in front and confirm it remains visible. Check resize, pause, alt-tab, and stale frames. A successful shader compile is not proof of occlusion.

## Sources

- [Unity 5.6 depth mode](https://docs.unity3d.com/ja/560/ScriptReference/DepthTextureMode.html)
- [Unity camera depth manual](https://docs.unity3d.com/es/530/Manual/SL-CameraDepthTexture.html)
- [GTA reference compositor](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/gta/src/compositor.cpp)
- [GTA depth shader](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/gta/shaders/MCPassthrough.fx)
- [Minecraft frame exporter](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/mc-forge/src/main/java/dev/rehan/passthrough/client/FrameExporter.java)
