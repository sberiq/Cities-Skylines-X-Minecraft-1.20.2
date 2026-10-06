# Common world coordinates

Use one right-handed common frame with X and Z horizontal and Y up. Keep Cities-native coordinates and Minecraft-native coordinates separate. Every adapter uses one `WorldTransform`; do not distribute scale constants or axis swaps.

Let `pM` be Minecraft position in blocks, `oM` its configured origin, `oC` the Cities origin, `s` Cities units per Minecraft block, and `R` the configured basis rotation:

```text
pCities = oC + R * (s * (pMinecraft - oM))
pMinecraft = oM + inverse(R) * (pCities - oC) / s
```

Unity and Minecraft both use Y-up vectors, but their world axis orientation, origin and yaw sign still need calibration. A starting scale of 1 is only a measurement hypothesis based on the GTA reference; it is not a Cities unit guarantee.

Use quaternions or a documented rotation matrix for orientation. Vectors use rotation only; linear velocities also convert units. Transform all eight AABB corners before rebuilding bounds. Keep FOV, aspect, near/far planes separate from world position.

Persist `scale`, origins, X/Y/Z translation and yaw/pitch/roll offsets in config. Calibration mode should draw both origins, axes, a reference marker, player feet, ground contact and camera forward vectors.

Add inverse round-trip tests for positions, vectors, rotations and bounds. Compare projected screen points only after the live game camera has been captured. No common transform is calibrated yet.
