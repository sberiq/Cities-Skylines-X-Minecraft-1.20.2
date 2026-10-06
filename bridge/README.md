# CitiesCraft Bridge

The Bridge relays state and input on `127.0.0.1:25598`, plus Minecraft's layered CCF3 frames on `127.0.0.1:25599`. Both listeners bind to localhost only.

At startup, the Bridge reads `config/citiescraft.properties` relative to its working directory. Edit the paired world anchors, scale and yaw before launching. `-Dcitiescraft.config=/path/to/file` overrides the config path.

Cities sends a Minecraft camera pose and input events; Minecraft supplies the player pose and frame layers. The Bridge maps the Minecraft position, yaw, depth scale and city collision snapshot using the same calibration. See [protocol details](../docs/PROTOCOL.md), [coordinates](../docs/COORDINATES.md) and [macOS installation](../docs/INSTALLATION.md).

## Layered frame socket

The Minecraft producer sends `CCFRAME/3<TAB>minecraft<LF>` and the Cities consumer sends `CCFRAME/3<TAB>cities<LF>`. The Bridge replies `CCFRAME/3<TAB>OK<LF>`.

Each CCF3 record has a 96-byte big-endian header followed by world RGBA8, positive linear depth float32, hand RGBA8 and GUI RGBA8. Its camera pose is captured with the image and transformed into Cities coordinates by Bridge. The Bridge validates dimensions and matching plane lengths, holds only the latest complete frame, and replaces old pending data instead of queuing an unbounded backlog. Full offsets and data conventions are in [docs/PROTOCOL.md](../docs/PROTOCOL.md).

Run with Java 17 using `./bin/bridge` from the unpacked distribution. State and frame ports can be changed with `-Dcitiescraft.port=...` and `-Dcitiescraft.framePort=...`.
