# Terrain and collision bridge

## Implemented path

Cities reads a local world snapshot around the transformed Minecraft player every 0.5 seconds. The query runs through the CS1 simulation queue and covers the configured 16–96 meter radius.

- Terrain height uses the installed `TerrainManager.SampleFinalHeightSmooth` API.
- Nearby road segment candidates come from `NetManager.GetClosestSegments`; the nearest segment's `GetClosestPosition` supplies road/deck height within its half-width. A road more than one unit below terrain is treated as underground and the terrain roof remains the walkable surface.
- Up to 128 nearby buildings become coarse bounds using their Cities position, rotation, prefab size and collision height. IDs include both building index and build index so a recycled index is distinguishable.
- Bridge maps height cells and all eight building-box corners to Minecraft coordinates. It sends one versioned snapshot to Minecraft.
- A Minecraft 1.20.2 Mixin adds these temporary `VoxelShape` boxes to vanilla's movement collision query. Vanilla still handles player input, jumping, movement, Minecraft blocks and step-up behavior. No Minecraft blocks or save data are changed.
- Minecraft bins immutable collision shapes by chunk and drops the snapshot when its world closes. The next complete city snapshot replaces it atomically.

Terrain samples are 8 meters apart. Each sample becomes an invisible vertical collision column, so slopes and road edges are stair-stepped. Rotated bounds become axis-aligned boxes and can be wider than a building's true footprint. Buildings are static; cars, citizens, props, trees and destruction are not collision proxies yet. Tunnel floors are not exported. Road samples provide support height, not road-wall geometry.

## Test in a live save

1. Calibrate the anchors in [COORDINATES.md](COORDINATES.md); without that, the player and streamed collision region can land at the wrong place.
2. Start Bridge, Cities and Minecraft. In Minecraft, the HUD should show a nonzero `City collision` count once Cities is loaded.
3. Walk across flat terrain and a road, then try a raised bridge if the save has one. Check for falling, floating and stair-step behavior.
4. Walk into a nearby building. Minecraft should stop at its approximate invisible bounds.
5. Leave the Minecraft world and verify the HUD count resets; reopen it and confirm a new snapshot arrives.

The local protocol and build checks do not replace these live game checks. API queries compile against the installed Steam game assemblies, but surface coverage and building bounds still require validation in the actual save.
