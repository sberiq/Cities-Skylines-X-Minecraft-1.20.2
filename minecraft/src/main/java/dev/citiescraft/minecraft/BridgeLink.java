package dev.citiescraft.minecraft;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Owns all blocking socket work; game and render callbacks only exchange snapshots. */
final class BridgeLink {
    private final AtomicReference<PlayerState> player = new AtomicReference<>();
    private volatile CameraState camera;
    private volatile boolean connected;
    private volatile boolean stopped;
    private volatile Socket activeSocket;

    void start() {
        Thread worker = new Thread(this::run, "citiescraft-bridge-link");
        worker.setDaemon(true);
        worker.start();
    }

    void publishPlayer(double x, double y, double z, float yaw, float pitch) {
        player.set(new PlayerState(x, y, z, yaw, pitch));
    }

    CameraState cameraState() { return camera; }
    boolean isConnected() { return connected; }

    void stop() {
        stopped = true;
        Socket socket = activeSocket;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void run() {
        long sequence = 0;
        while (!stopped) {
            try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), 25598);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write("HELLO\t1\tminecraft\n");
                writer.flush();
                String welcome = reader.readLine();
                if (welcome == null || !welcome.startsWith("WELCOME\t1\t")) throw new IOException("Bridge rejected Minecraft client");
                activeSocket = socket;
                connected = true;
                Thread receiver = new Thread(() -> readCameraLoop(socket, reader), "citiescraft-bridge-reader");
                receiver.setDaemon(true);
                receiver.start();
                long lastSend = 0;
                while (!stopped && connected && !socket.isClosed()) {
                    PlayerState state = player.get();
                    long now = System.nanoTime();
                    if (state != null && now - lastSend >= 100_000_000L) {
                        String line = String.format(Locale.ROOT, "PLAYER\t%d\t%.6f\t%.6f\t%.6f\t%.4f\t%.4f\n",
                                sequence++, state.x, state.y, state.z, state.yaw, state.pitch);
                        writer.write(line);
                        writer.flush();
                        lastSend = now;
                    }
                    try { Thread.sleep(25); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
                }
            } catch (IOException exception) {
                // A stopped Bridge is normal; the next pass reconnects.
            } finally {
                connected = false;
                activeSocket = null;
            }
            if (!stopped) {
                try { Thread.sleep(1000); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void readCameraLoop(Socket socket, BufferedReader reader) {
        try {
            String line;
            while (!stopped && (line = reader.readLine()) != null) parseCamera(line);
        } catch (IOException ignored) {
            // The sender loop will reconnect after the socket closes.
        } finally {
            connected = false;
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void parseCamera(String line) {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 8 || !"CAMERA".equals(fields[0])) return;
        try {
            camera = new CameraState(Double.parseDouble(fields[2]), Double.parseDouble(fields[3]),
                    Double.parseDouble(fields[4]), Double.parseDouble(fields[5]),
                    Double.parseDouble(fields[6]), Double.parseDouble(fields[7]));
        } catch (NumberFormatException ignored) { }
    }

    private static final class PlayerState {
        final double x, y, z;
        final float yaw, pitch;
        PlayerState(double x, double y, double z, float yaw, float pitch) {
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        }
    }

    static final class CameraState {
        final double x, y, z, yaw, pitch, fov;
        CameraState(double x, double y, double z, double yaw, double pitch, double fov) {
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch; this.fov = fov;
        }
    }
}
