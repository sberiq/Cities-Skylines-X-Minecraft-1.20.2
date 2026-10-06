# Bridge protocol

The first state path is a Bridge process on `127.0.0.1:25598`. Minecraft and Cities connect as TCP clients. Messages are UTF-8 tab-separated records ending in newline. TCP carries state only, not rendered images.

```text
HELLO<TAB>1<TAB>minecraft
HELLO<TAB>1<TAB>cities
WELCOME<TAB>1<TAB><session-id>
PLAYER<TAB><seq><TAB><x><TAB><y><TAB><z><TAB><yaw><TAB><pitch>
CAMERA<TAB><seq><TAB><x><TAB><y><TAB><z><TAB><yaw><TAB><pitch><TAB><vertical-fov>
ERROR<TAB><code><TAB><message>
```

One peer is allowed per role. The Bridge relays valid `PLAYER` only from Minecraft to Cities and `CAMERA` only from Cities to Minecraft. Reject wrong roles, non-finite values, unsupported versions, malformed records, duplicate peers and lines above 4096 bytes. Bind loopback only.

`PLAYER` XYZ are Minecraft-native blocks. `CAMERA` XYZ are Cities-native world units. The initial overlays display source-native values; the common transform is applied in a later phase. Each sender increments its sequence; receivers discard stale state.

Socket workers transfer immutable snapshots. Unity and Minecraft state is read or written only on their main/client threads. Render planes later use a separate versioned shared-memory ring with the same frame/pose sequence.
