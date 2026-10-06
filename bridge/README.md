# CitiesCraft Bridge

The Bridge keeps the versioned telemetry relay on `127.0.0.1:25598` and exposes a separate binary frame relay on `127.0.0.1:25599`.

At startup, it reads `config/citiescraft.properties` relative to its working directory (override with `-Dcitiescraft.config=/path/to/file`). Edit the Minecraft/Cities anchor pair, scale and yaw there before starting the Bridge. The state channel maps player poses and bounded terrain/building collision snapshots; see [`docs/PROTOCOL.md`](../docs/PROTOCOL.md) and [`docs/COORDINATES.md`](../docs/COORDINATES.md).

## Frame socket

Connect one Minecraft producer and one Cities consumer. The client starts with one ASCII line, including the final line-feed byte:

`CCFRAME/1<TAB>minecraft<LF>` for the Minecraft producer, or `CCFRAME/1<TAB>cities<LF>` for the Cities consumer.

The Bridge replies `CCFRAME/1<TAB>OK<LF>`. The Minecraft peer then writes zero or more frames. The Cities peer receives the most recently published frame immediately when one exists, followed by newer frames as they arrive. A single latest-frame slot replaces frames not yet picked up by the Cities writer; sequence numbers that do not increase within a Minecraft connection are discarded. At most one frame is in flight to Cities while newer frames replace the latest slot.

Each frame is a 24-byte big-endian header followed by tightly packed RGBA8 pixels:

| Offset | Type | Meaning |
| ---: | --- | --- |
| 0 | 4 bytes | ASCII magic `CCF1` |
| 4 | int32 | Width, 1–640 |
| 8 | int32 | Height, 1–360 |
| 12 | int64 | Non-negative frame sequence |
| 20 | int32 | Payload bytes; must equal `width * height * 4` |
| 24 | bytes | RGBA8 pixels, row order as supplied by the producer |

Only Minecraft may publish frames. Cities is receive-only. Invalid handshakes, headers, dimensions, lengths, and truncated frames close that peer. The listener binds to IPv4 loopback only. The Bridge retains only one complete latest frame for a Cities peer that connects later; output frame sequence numbers stay increasing when Minecraft reconnects.

Run with Java 17 using `./bin/bridge` from the unpacked distribution. The state and frame ports can be changed independently with `-Dcitiescraft.port=...` and `-Dcitiescraft.framePort=...`.
