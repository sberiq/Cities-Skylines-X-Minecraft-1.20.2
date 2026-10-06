# Collision and nearby city data

## Current status

No city geometry, road surface, vehicle, citizen or collision proxy is streamed yet. The Cities client currently sends camera telemetry only, and Minecraft currently sends player telemetry only.

## Planned first collision slice

Start with a bounded query around the Minecraft player. Cities remains authoritative for city objects. Send terrain height samples and coarse building bounds with stable object IDs; Minecraft creates invisible, owned collision proxies only near the player. Do not voxelize the city.

Messages should be versioned add/update/remove records and include a sequence, stable source ID, transform, bounds, and expiry or unload semantics. The Bridge should cull by configured radius and avoid resending unchanged objects. Minecraft must delete only proxies owned by this mod when a city snapshot changes or the world unloads.

Roads, bridges and tunnels need surface samples or simplified collision geometry beyond a terrain-height query. Cars and citizens can begin as visual telemetry; dynamic collision is a later phase. Stable Cities IDs, geometry access and query budget still need validation against the installed CS1 assemblies and a live save.

## Acceptance checks

1. Walk over a flat road without falling through or visibly floating.
2. Walk into and around one test building without passing through its proxy.
3. Unload the save and confirm owned proxies are removed.
4. Move beyond the configured radius and confirm stale proxies are removed.
5. Profile query and update cost with a repeatable test city.
