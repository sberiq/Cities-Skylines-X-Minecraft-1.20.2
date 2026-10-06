# Bridge protocol

The Bridge binds only to IPv4 loopback:

- `127.0.0.1:25598` carries UTF-8 tab-separated state lines, each ending in LF. State lines are capped at 64 KiB.
- `127.0.0.1:25599` carries the versioned binary RGBA frame stream described in [the Bridge README](../bridge/README.md).

## State channel

Each game connects as one peer per role:

```text
HELLO<TAB>1<TAB>minecraft
HELLO<TAB>1<TAB>cities
WELCOME<TAB>1<TAB><session-id>
SETTINGS<TAB><collision-radius-in-Cities-units>
PLAYER<TAB><seq><TAB><x><TAB><y><TAB><z><TAB><yaw><TAB><pitch>
CAMERA<TAB><seq><TAB><x><TAB><y><TAB><z><TAB><yaw><TAB><pitch><TAB><vertical-fov>
ERROR<TAB><code><TAB><message>
```

Minecraft sends native block coordinates in `PLAYER`. Bridge applies the configured scale, anchor and yaw rotation before forwarding the same record shape to Cities. Cities sends native world coordinates in `CAMERA`; Bridge maps that pose back to Minecraft before forwarding it. One peer is allowed per role. Sequences must increase on each connection; numeric values must be finite.

Cities sends one complete bounded world snapshot at a time:

```text
SNAPSHOT<TAB>seq<TAB>centerX<TAB>centerZ<TAB>spacing<TAB>rows<TAB>columns<TAB><row-major heights...><TAB>boxCount<TAB><id,minX,minY,minZ,maxX,maxY,maxZ>...
```

Grid dimensions are 3–65 per side; height count is exactly `rows * columns`. City bounds use Unity world coordinates. Bridge transforms each terrain cell and the eight corners of each building bound, then sends Minecraft-native collision boxes:

```text
CITYWORLD<TAB>bridge-seq<TAB>T,minX,minY,minZ,maxX,maxY,maxZ<TAB>B,minX,minY,minZ,maxX,maxY,maxZ...
```

`T` means a terrain/road support column; `B` means a building obstacle. The snapshot is immutable and replaces the prior one in Minecraft. The Bridge assigns output sequence numbers across Cities reconnects so stale world snapshots are rejected safely.

## Threading and validation

Bridge validates role, version, sequence, finite values, grid dimensions, box volumes and line size. Unity queries the city on its simulation queue; only the Cities network worker writes sockets. Minecraft's socket worker parses and builds immutable shape snapshots; Minecraft's movement Mixin adds shapes to vanilla collision checks. Frame transfer is separate and never encoded in these state records.
