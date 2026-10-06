# Bridge protocol

The Bridge binds only to IPv4 loopback:

- `127.0.0.1:25598` carries UTF-8 tab-separated state records ending in LF, capped at 64 KiB.
- `127.0.0.1:25599` carries versioned CCF3 frame records.

## State channel

Each game connects as one peer per role:

```text
HELLO<TAB>1<TAB>minecraft
HELLO<TAB>1<TAB>cities
WELCOME<TAB>1<TAB><session-id>
SETTINGS<TAB><collision-radius-cities><TAB><cities-units-per-minecraft-block>
PLAYER<TAB><seq><TAB><x><TAB><y><TAB><z><TAB><yaw><TAB><pitch>
VIEW<TAB><seq><TAB><eye-x><TAB><eye-y><TAB><eye-z><TAB><yaw><TAB><pitch><TAB><vertical-fov><TAB><aspect>
CAMERA<TAB><seq><TAB><x><TAB><y><TAB><z><TAB><yaw><TAB><pitch><TAB><vertical-fov>
INPUT<TAB><seq><TAB><type><TAB><type-specific-fields...>
ERROR<TAB><code><TAB><message>
```

Minecraft publishes feet position in `PLAYER` and the rendered eye pose in `VIEW`. Bridge maps both into Cities units and changes Minecraft yaw to Unity yaw. Cities sends input events; Bridge forwards them to Minecraft. `KEY` uses GLFW key, scan code, action (`0` release, `1` press, `2` repeat) and modifier fields; `CURSOR` uses normalized coordinates; `LOOK` uses relative pixel deltas; `BUTTON`, `SCROLL` and Unicode `TEXT` follow the same ordered sequence.

Cities also publishes a sampled world snapshot:

```text
SNAPSHOT<TAB>seq<TAB>centerX<TAB>centerZ<TAB>spacing<TAB>rows<TAB>columns<TAB><row-major heights...><TAB>boxCount<TAB><id,minX,minY,minZ,maxX,maxY,maxZ>...
CITYWORLD<TAB>bridge-seq<TAB>T,minX,minY,minZ,maxX,maxY,maxZ<TAB>B,minX,minY,minZ,maxX,maxY,maxZ...
```

`T` denotes terrain/road support; `B` denotes a static building bound. These shapes are temporary collision and raycast proxies, not actual Minecraft blocks. The Bridge transforms all eight corners of each bound into Minecraft coordinates.

## CCF3 frame channel

Connect with `CCFRAME/3<TAB>minecraft<LF>` or `CCFRAME/3<TAB>cities<LF>`. Bridge replies `CCFRAME/3<TAB>OK<LF>`. Minecraft is the producer; Cities is the consumer. A single latest complete frame is retained, so slow consumers skip stale frames.

Each record begins with a 96-byte big-endian header, then four planes in this order: world RGBA8, positive linear camera-space depth, hand RGBA8, GUI RGBA8. The header carries the Minecraft camera pose used to render that same frame. Bridge transforms the pose into Cities coordinates before forwarding the frame so camera and pixels stay paired during movement.

| Offset | Type | Meaning |
| ---: | --- | --- |
| 0 | 4 bytes | ASCII `CCF3` |
| 4 | int32 | Width, 1–640 |
| 8 | int32 | Height, 1–360 |
| 12 | int64 | Non-negative sequence |
| 20 | int64 | Minecraft capture timestamp in nanoseconds |
| 28 | int32 | World RGBA byte count; `width * height * 4` |
| 32 | int32 | Depth byte count; `width * height * 4` |
| 36 | int32 | Hand RGBA byte count; `width * height * 4` |
| 40 | int32 | GUI RGBA byte count; `width * height * 4` |
| 44 | int32 | Depth encoding `1` = IEEE-754 float32 metres |
| 48 | float32 | Minecraft camera near plane in blocks |
| 52 | float32 | Minecraft camera far plane in blocks |
| 56 | float64 | Camera X after mapping from Minecraft to Cities |
| 64 | float64 | Camera Y after mapping from Minecraft to Cities |
| 72 | float64 | Camera Z after mapping from Minecraft to Cities |
| 80 | float32 | Unity yaw after mapping |
| 84 | float32 | Camera pitch |
| 88 | float32 | Vertical field of view in degrees |
| 92 | float32 | Captured aspect ratio |
| 96 | bytes | World, depth, hand and GUI planes |

RGBA planes use top-left row order on the wire and straight alpha for hand/GUI. Depth is a big-endian float32; clear pixels may be positive infinity. Each plane is bounded to 640×360. The Cities adapter flips rows for Unity textures and scales Minecraft depth by the configured world scale before comparing against city depth.

## Validation and threading

The Bridge validates roles, sequences, finite camera metadata, dimensions, layer lengths, depth encoding and build limits. Cities queries the game world on its simulation queue; socket operations run on network workers. Minecraft queues received input and applies callbacks on its client thread. One peer per role is allowed; all connections retry after a disconnect.
