# Coordinate mapping and calibration

Minecraft reports player feet in block coordinates and its rendered camera in eye coordinates. Cities uses Unity world units, with Y as up. The Bridge maps both positions using one origin pair, one scale and one horizontal yaw offset.

For Minecraft position `(x,y,z)`, Minecraft origin `oM`, Cities origin `oC`, scale `s` (Cities units per Minecraft block), and yaw offset `θ`:

```text
dx = (x - oM.x) * s
dy = (y - oM.y) * s
dz = (z - oM.z) * s

Cities.x = oC.x + cos(θ) * dx + sin(θ) * dz
Cities.y = oC.y + dy
Cities.z = oC.z - sin(θ) * dx + cos(θ) * dz
Cities.yaw = wrap(θ - Minecraft.yaw)
```

Yaw is converted from Minecraft's convention to Unity's convention. The inverse maps Cities camera and collision positions back into Minecraft. Position translation is not applied to directions; yaw rotates separately. Minecraft camera eye coordinates drive the Cities view, while the paired origins are defined using player feet and city ground.

## Detailed first calibration

1. Load the target Cities save with the CitiesCraft mod enabled. Press **F8** if passthrough hides the calibration label, then pan the Cities camera target over the road or sidewalk to use as the starting location.
2. Read the overlay's `Cities anchor` X, **ground Y**, and Z. Ground Y is the surface height; it is different from the elevated camera position.
3. In the Minecraft void world, stand at the position you want associated with that surface. Open **F3**, note `XYZ` (feet coordinates), and keep those numbers.
4. Paste the two triples into `world.cities-origin-x/y/z` and `world.minecraft-origin-x/y/z` in the Bridge config. Use matching coordinate order: X→X, Y→Y, Z→Z.
5. Leave `world.scale=1.0` and `world.yaw-offset-degrees=0.0`, save, and restart Bridge.
6. With the player still at the saved Minecraft XYZ, focus Cities and enable passthrough with **F8**. Compare the mapped feet with the selected street point.
7. If height is wrong, change only `world.cities-origin-y`. If horizontal size is wrong, adjust `world.scale`. If forward direction is wrong, change `world.yaw-offset-degrees`. Restart Bridge and recheck after each adjustment.

There is no shared landmark to infer between the saves. You choose which Minecraft point maps to which Cities point. The first mapping therefore establishes a shared location; it does not reconstruct geographic relationships between the two games.

## Settings

`config/citiescraft.properties` beside the extracted Bridge contains the shared settings:

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

Scale must be finite and greater than zero. Yaw and all origins must be finite. Collision radius is limited to 16–96 Cities units; the Bridge sends it to Cities when the game connects.

## Scope and verification

Cities terrain is sampled on an 8-unit grid. Nearby static building bounds are approximate axis-aligned boxes. Minecraft receives these shapes as temporary collision/raycast proxies, not as generated blocks. Moving traffic, citizens, props and tunnel floors are not fully represented.

The Bridge smoke check validates nonzero scale, yaw conversion, origin mapping and transformed bounds. It does not validate the world height, direction, collision fit or projection in a running game. Those depend on the chosen Cities save and must be checked in-game.
