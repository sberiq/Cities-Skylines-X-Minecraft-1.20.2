# Passthrough rendering

Cities: Skylines 1 owns the final view. Its gameplay camera follows Minecraft's mapped eye position, yaw, pitch, vertical field of view and aspect. The Minecraft client renders a 640×360 frame and exports four same-frame layers through the local Bridge: world color, positive linear depth, first-person hand and GUI/HUD. Cities samples its own camera depth, draws whichever world surface is nearer, then draws Minecraft's hand and GUI over the result.

Minecraft stays open in a separate window and continues ticking in the background. Cities receives key, mouse, scroll and text events and forwards them to the Minecraft client. F8 in Cities enables or disables passthrough and remote input. The Minecraft void preset helps the city scene fill areas where a Minecraft landscape would otherwise render.

## Current implementation

- The Minecraft mod captures the world after `WorldRenderer.render`, captures the hand around `renderHand`, and captures HUD/screens while the main framebuffer is drawn. Triple-buffered OpenGL PBOs and fences provide asynchronous readback.
- CCF3 carries four RGBA/depth planes plus the camera pose captured with them. Bridge transforms that pose and keeps only the latest complete frame, so Cities renders from the pose that produced its image.
- Cities uploads the layers to Unity textures and attaches `CitiesNativeCompositor` to the gameplay camera. Its native macOS OpenGL shader compares Minecraft linear depth to the Cities camera depth, composites color, and places hand/HUD on top.
- The view/camera transform and keyboard/mouse input pass through the state channel. City terrain and nearby static buildings become approximate Minecraft collision and crosshair proxies.

The Java and C# projects compile, but the compositor has not been launched inside Cities. Texture orientation, Unity depth parameters, native OpenGL state, alpha, resizing, and the final callback order are therefore still integration risks. On setup failure the effect logs a warning and shows the Cities image only. Minecraft output is fixed at 640×360 and captured before some post-effects and entity outlines; city depth is limited to what Unity's camera depth texture includes. The mod temporarily disables Minecraft view bobbing and dynamic FOV effects to keep its projection aligned with the host camera, restoring both values when Minecraft closes.

## First live acceptance check

1. Start both games in 16:9 windows, use an open city street and the Minecraft void world, then calibrate the coordinate pair.
2. Put the Minecraft player at the anchor. In Cities, enable F8. Confirm the world fills the view and the hand/HUD are visible.
3. Look at a city building edge. Move the Minecraft camera so Minecraft geometry crosses it; the nearer surface should cover the farther one.
4. Check W/A/S/D, jump, hotbar, E inventory, mouse look, left-click and right-click while Cities has focus.
5. Check that city ground supports the player and nearby static building bounds stop movement. Confirm the player can still move and place normal Minecraft blocks in the void world.
6. Resize, alt-tab and reconnect Bridge; check that stale input is released and the regular Cities camera returns when passthrough goes stale or is switched off.

This is a first usable prototype, not full gameplay parity. Cities assets cannot be mined as Minecraft blocks. Moving cars, citizens, props and tunnel floor behavior remain unsupported. City-proxy placement and collision need testing in the actual save.

## Reference design

The GTA passthrough project uses the same broad pattern—host camera, Minecraft color/depth, separate overlay, and depth-aware composition—but has GTA-specific camera, input and renderer APIs. This project does not reuse its Windows shared memory or Direct3D/ReShade compositor.

## Sources

- [Unity 5.6 depth mode](https://docs.unity3d.com/ja/560/ScriptReference/DepthTextureMode.html)
- [Unity camera depth manual](https://docs.unity3d.com/es/530/Manual/SL-CameraDepthTexture.html)
- [GTA reference compositor](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/gta/src/compositor.cpp)
- [GTA depth shader](https://github.com/VortexisTV/wither-storm-gta5-passthrough/blob/main/gta/shaders/MCPassthrough.fx)
