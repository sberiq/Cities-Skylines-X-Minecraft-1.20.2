# Troubleshooting

## Bridge does not start

- Confirm Java 17 is active (`java -version`).
- Confirm port 25598 is free.
- The Bridge binds only to `127.0.0.1`; both games must run on the same computer.
- If Gradle cannot write its cache, set `GRADLE_USER_HOME` to a writable directory.

## A client shows disconnected

- Start the Bridge before or after either game; both clients retry connections.
- Check that both clients use protocol version 1 and that no second copy of the same role is connected.
- Check the Bridge console for connect/disconnect messages.

## Cities project cannot resolve game assemblies

Set `CS1_INSTALL` or `CITIES_SKYLINES_MANAGED` to the directory containing the CS1 managed DLLs. The Cities mod requires Windows and the game's assembly versions; other versions have not been validated.

## Minecraft mod does not load

Check that the actual client is Minecraft 1.20.2, Java 17 is selected, and Fabric Loader/API match the pinned versions in `docs/MINECRAFT_1_20_2.md`. No launch has yet been verified from this host.
