# Common world coordinates and calibration

The Bridge owns one transform shared by the Cities and Minecraft adapters. Minecraft positions are block coordinates; Cities positions are Unity world units. Both use Y-up, but each has its own origin and may have a different horizontal orientation.

For Minecraft point `(x, y, z)`, Minecraft anchor `oM`, Cities anchor `oC`, scale `s` (Cities units per Minecraft block), and yaw offset `θ`:

```text
dx = (x - oM.x) * s
dy = (y - oM.y) * s
dz = (z - oM.z) * s

Cities.x = oC.x + cos(θ) * dx + sin(θ) * dz
Cities.y = oC.y + dy
Cities.z = oC.z - sin(θ) * dx + cos(θ) * dz
Cities.yaw = wrap(Minecraft.yaw - θ)
```

The inverse transform maps Cities camera positions and collision geometry back into Minecraft. It transforms all eight corners of an obstacle bound and builds an axis-aligned Minecraft collision box. Position translation is never applied to directions; yaw rotates separately.

## Pick the anchor pair

There is no common landmark in the two games, so a save cannot be auto-aligned. Choose the Cities spot where the Minecraft player should begin, then assign that spot the Minecraft player's current coordinates.

1. Start Cities and load the save. The CitiesCraft overlay shows `Cities anchor`: the camera's target X/Z and sampled terrain height Y. Pan the city camera until that target is on the road or sidewalk where the Minecraft player should start.
2. In Minecraft, stand where you want the corresponding player origin to be. Press **F3** and record the player's `XYZ` (feet position).
3. Edit `config/citiescraft.properties` beside the extracted Bridge launcher. Put the Cities overlay coordinates in `world.cities-origin-x/y/z` and the F3 coordinates in `world.minecraft-origin-x/y/z`.
4. Use `world.scale=1.0` and `world.yaw-offset-degrees=0.0` for a first run. One Cities world unit is approximately one meter and one Minecraft block is one meter. Adjust yaw if the streets run at a different angle to the Minecraft axes.
5. Restart the Bridge after editing the file. Its startup log confirms which calibration file it loaded.

The anchors should describe matching positions: at Minecraft origin, the player feet should map to the Cities ground point. Set the Cities Y origin to the displayed ground height, not the elevated camera height. If a player sinks or floats, adjust the vertical anchor using the overlays and try again.

## Settings

`config/citiescraft.properties` is the single settings source:

```properties
world.scale=1.0
world.yaw-offset-degrees=0.0
world.minecraft-origin-x=0.0
world.minecraft-origin-y=64.0
world.minecraft-origin-z=0.0
world.cities-origin-x=0.0
world.cities-origin-y=0.0
world.cities-origin-z=0.0
collision.radius-cities-units=96.0
```

Collision radius is limited to 16–96 Cities units so terrain and geometry snapshots remain bounded. The Bridge sends the selected radius to Cities when it connects. Scale, origins and yaw must be finite; scale must be greater than zero.

## Verification

The Bridge smoke check exercises a nonzero scale, a 90-degree yaw, an offset anchor, inverse pose conversion and transformed terrain/building bounds. It verifies math and protocol routing without launching either game. Actual scale, road alignment, player feet height and collision fit must be checked in the user's loaded save.
