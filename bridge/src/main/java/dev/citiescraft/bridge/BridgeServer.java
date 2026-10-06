package dev.citiescraft.bridge;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Localhost telemetry relay with a bounded latest-frame TCP channel. */
public final class BridgeServer implements AutoCloseable {
    private static final int PORT = Integer.getInteger("citiescraft.port", 25598);
    private static final int FRAME_PORT = Integer.getInteger("citiescraft.framePort", 25599);
    private static final int MAX_LINE_BYTES = 64 * 1024;
    private static final int FRAME_HEADER_BYTES = 24;
    private static final int MAX_FRAME_WIDTH = 640;
    private static final int MAX_FRAME_HEIGHT = 360;
    private static final int MAX_FRAME_BYTES = MAX_FRAME_WIDTH * MAX_FRAME_HEIGHT * 4;
    private static final byte[] FRAME_MAGIC = {'C', 'C', 'F', '1'};
    private static final byte[] FRAME_HANDSHAKE_OK = "CCFRAME/1\tOK\n".getBytes(StandardCharsets.US_ASCII);
    private static final BridgeConfig CONFIG = BridgeConfig.load();

    private final Object peersLock = new Object();
    private final Object frameLock = new Object();
    private final ExecutorService clients = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "citiescraft-bridge-client");
        thread.setDaemon(true);
        return thread;
    });
    private Peer minecraft;
    private Peer cities;
    private FramePeer frameMinecraft;
    private FramePeer frameCities;
    private FrameSnapshot latestFrame;
    private long frameGeneration;
    private long nextOutputSequence;
    private long nextCityWorldSequence;
    private String latestPlayer;
    private String latestCamera;
    private String latestCityWorld;
    private volatile boolean closed;
    private ServerSocket server;
    private ServerSocket frameServer;

    public static void main(String[] args) throws IOException {
        try (BridgeServer bridge = new BridgeServer()) {
            bridge.serve();
        }
    }

    private void serve() throws IOException {
        server = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
        try {
            frameServer = new ServerSocket(FRAME_PORT, 4, InetAddress.getByName("127.0.0.1"));
        } catch (IOException exception) {
            server.close();
            throw exception;
        }
        clients.execute(this::acceptFrames);
        System.out.println("CitiesCraft Bridge listening on 127.0.0.1:" + PORT
                + " (state) and 127.0.0.1:" + FRAME_PORT + " (frames)");
        while (!closed) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                clients.execute(() -> handle(socket));
            } catch (IOException exception) {
                if (!closed) throw exception;
            }
        }
    }

    private void acceptFrames() {
        while (!closed) {
            try {
                Socket socket = frameServer.accept();
                socket.setTcpNoDelay(true);
                clients.execute(() -> handleFrame(socket));
            } catch (IOException exception) {
                if (!closed) System.err.println("Frame relay listener stopped: " + exception.getMessage());
                return;
            }
        }
    }

    private void handleFrame(Socket socket) {
        FramePeer peer = null;
        try (Socket client = socket) {
            InputStream input = client.getInputStream();
            Role role = readFrameHandshake(input);
            if (role == null) return;

            peer = new FramePeer(role, client);
            synchronized (frameLock) {
                if ((role == Role.MINECRAFT && frameMinecraft != null)
                        || (role == Role.CITIES && frameCities != null)) {
                    return;
                }
                if (role == Role.MINECRAFT) {
                    frameMinecraft = peer;
                    frameGeneration++;
                } else {
                    frameCities = peer;
                }
                frameLock.notifyAll();
            }

            OutputStream output = client.getOutputStream();
            output.write(FRAME_HANDSHAKE_OK);
            output.flush();
            System.out.println("Frame connected: " + role.wireName);

            if (role == Role.MINECRAFT) {
                readMinecraftFrames(peer, new DataInputStream(input));
            } else {
                writeLatestFrames(peer, new DataOutputStream(output));
            }
        } catch (IOException ignored) {
            // Frame peers may disconnect while either game or Bridge is restarting.
        } finally {
            if (peer != null) {
                synchronized (frameLock) {
                    if (frameMinecraft == peer) frameMinecraft = null;
                    if (frameCities == peer) frameCities = null;
                    frameLock.notifyAll();
                }
                System.out.println("Frame disconnected: " + peer.role.wireName);
            }
        }
    }

    private static Role readFrameHandshake(InputStream input) throws IOException {
        String line = readAsciiLine(input, 64);
        if ("CCFRAME/1\tminecraft".equals(line)) return Role.MINECRAFT;
        if ("CCFRAME/1\tcities".equals(line)) return Role.CITIES;
        return null;
    }

    private void readMinecraftFrames(FramePeer peer, DataInputStream input) throws IOException {
        while (!closed && !peer.socket.isClosed()) {
            byte[] header = new byte[FRAME_HEADER_BYTES];
            try {
                input.readFully(header);
            } catch (EOFException exception) {
                return;
            }

            ByteBuffer fields = ByteBuffer.wrap(header);
            for (byte expected : FRAME_MAGIC) {
                if (fields.get() != expected) throw new IOException("bad frame magic");
            }
            int width = fields.getInt();
            int height = fields.getInt();
            long sequence = fields.getLong();
            int payloadLength = fields.getInt();
            long expectedLength = (long) width * (long) height * 4L;
            if (width <= 0 || width > MAX_FRAME_WIDTH || height <= 0 || height > MAX_FRAME_HEIGHT
                    || sequence < 0 || expectedLength <= 0 || expectedLength > MAX_FRAME_BYTES
                    || payloadLength != expectedLength) {
                throw new IOException("invalid frame dimensions, sequence, or payload length");
            }

            byte[] rgba = new byte[payloadLength];
            input.readFully(rgba);
            synchronized (frameLock) {
                if (frameMinecraft != peer) return;
                if (sequence <= peer.lastSequence) continue;
                peer.lastSequence = sequence;
                if (nextOutputSequence < 0) throw new IOException("frame output sequence exhausted");
                long outputSequence = nextOutputSequence++;
                latestFrame = new FrameSnapshot(frameGeneration, width, height, outputSequence, rgba);
                frameLock.notifyAll();
            }
        }
    }

    private void writeLatestFrames(FramePeer peer, DataOutputStream output) throws IOException {
        long lastGeneration = -1;
        long lastSequence = -1;
        while (!closed && !peer.socket.isClosed()) {
            FrameSnapshot frame;
            synchronized (frameLock) {
                while (!closed && frameCities == peer
                        && (latestFrame == null || (latestFrame.generation == lastGeneration
                        && latestFrame.sequence <= lastSequence))) {
                    try {
                        frameLock.wait();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (closed || frameCities != peer) return;
                frame = latestFrame;
            }

            writeFrame(output, frame);
            lastGeneration = frame.generation;
            lastSequence = frame.sequence;
        }
    }

    private static void writeFrame(DataOutputStream output, FrameSnapshot frame) throws IOException {
        output.write(FRAME_MAGIC);
        output.writeInt(frame.width);
        output.writeInt(frame.height);
        output.writeLong(frame.sequence);
        output.writeInt(frame.rgba.length);
        output.write(frame.rgba);
        output.flush();
    }

    private static String readAsciiLine(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) return null;
            if (value == '\n') return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
            if (value > 0x7f || value == '\r' || bytes.size() >= maxBytes) {
                throw new IOException("invalid frame handshake line");
            }
            bytes.write(value);
        }
    }

    private void handle(Socket socket) {
        Peer peer = null;
        try (Socket client = socket) {
            String hello = readLine(client.getInputStream());
            if (hello == null) return;
            String[] fields = hello.split("\\t", -1);
            if (fields.length != 3 || !"HELLO".equals(fields[0]) || !"1".equals(fields[1])) {
                sendError(client, "hello", "expected HELLO<TAB>1<TAB>role");
                return;
            }
            Role role = Role.parse(fields[2]);
            if (role == null) {
                sendError(client, "role", "role must be minecraft or cities");
                return;
            }

            peer = new Peer(role, client);
            synchronized (peersLock) {
                if ((role == Role.MINECRAFT && minecraft != null)
                        || (role == Role.CITIES && cities != null)) {
                    sendError(client, "duplicate", "a peer for this role is already connected");
                    return;
                }
                if (role == Role.MINECRAFT) minecraft = peer;
                else cities = peer;
                peer.send("WELCOME\t1\t" + UUID.randomUUID());
                peer.send("SETTINGS\t" + CONFIG.collisionRadius);
                if (role == Role.MINECRAFT) {
                    if (latestCamera != null) peer.send(latestCamera);
                    if (latestCityWorld != null) peer.send(latestCityWorld);
                } else if (latestPlayer != null) {
                    peer.send(latestPlayer);
                }
            }
            System.out.println("Connected: " + role.wireName);

            InputStream input = client.getInputStream();
            while (!closed) {
                String line = readLine(input);
                if (line == null) break;
                try {
                    relay(peer, line);
                } catch (ProtocolException exception) {
                    peer.send("ERROR\t" + exception.code + "\t" + exception.getMessage());
                    break;
                }
            }
        } catch (IOException ignored) {
            // Disconnects are expected while the game or Bridge is restarting.
        } finally {
            if (peer != null) {
                synchronized (peersLock) {
                    if (minecraft == peer) minecraft = null;
                    if (cities == peer) cities = null;
                }
                System.out.println("Disconnected: " + peer.role.wireName);
            }
        }
    }

    private void relay(Peer sender, String line) throws IOException, ProtocolException {
        String[] fields = line.split("\\t", -1);
        if (sender.role == Role.MINECRAFT && fields.length == 7 && "PLAYER".equals(fields[0])) {
            long sequence = parseSequence(fields[1]);
            for (int i = 2; i <= 6; i++) parseFinite(fields[i]);
            if (sequence <= sender.lastSequence) return;
            sender.lastSequence = sequence;
            double x = parseFinite(fields[2]);
            double y = parseFinite(fields[3]);
            double z = parseFinite(fields[4]);
            double yaw = parseFinite(fields[5]);
            double pitch = parseFinite(fields[6]);
            WorldTransform.Point city = CONFIG.transform.toCities(x, y, z);
            String mapped = "PLAYER\t" + sequence + "\t" + city.x + "\t" + city.y + "\t" + city.z
                    + "\t" + CONFIG.transform.yawToCities(yaw) + "\t" + pitch;
            synchronized (peersLock) {
                latestPlayer = mapped;
                if (cities != null) cities.send(mapped);
            }
            return;
        }

        if (sender.role == Role.CITIES && fields.length == 8 && "CAMERA".equals(fields[0])) {
            long sequence = parseSequence(fields[1]);
            for (int i = 2; i <= 6; i++) parseFinite(fields[i]);
            double fov = parseFinite(fields[7]);
            if (fov <= 0.0 || fov >= 180.0) {
                throw new ProtocolException("value", "vertical FOV must be between 0 and 180 degrees");
            }
            if (sequence <= sender.lastSequence) return;
            sender.lastSequence = sequence;
            WorldTransform.Point minecraftPoint = CONFIG.transform.toMinecraft(
                    parseFinite(fields[2]), parseFinite(fields[3]), parseFinite(fields[4]));
            String mapped = "CAMERA\t" + sequence + "\t" + minecraftPoint.x + "\t" + minecraftPoint.y
                    + "\t" + minecraftPoint.z + "\t" + CONFIG.transform.yawToMinecraft(parseFinite(fields[5]))
                    + "\t" + parseFinite(fields[6]) + "\t" + fov;
            synchronized (peersLock) {
                latestCamera = mapped;
                if (minecraft != null) minecraft.send(mapped);
            }
            return;
        }

        if (sender.role == Role.CITIES && fields.length >= 9 && "SNAPSHOT".equals(fields[0])) {
            String mapped = mapCitySnapshot(sender, fields);
            if (mapped == null) return;
            synchronized (peersLock) {
                latestCityWorld = mapped;
                if (minecraft != null) minecraft.send(mapped);
            }
            return;
        }

        throw new ProtocolException("message", "message is malformed or not allowed for this role");
    }

    private String mapCitySnapshot(Peer sender, String[] fields) throws IOException, ProtocolException {
        long sequence = parseSequence(fields[1]);
        if (sequence <= sender.lastSnapshotSequence) return null;
        double centerX = parseFinite(fields[2]);
        double centerZ = parseFinite(fields[3]);
        double spacing = parseFinite(fields[4]);
        int rows = parseBoundedInt(fields[5], 3, 65, "terrain rows");
        int columns = parseBoundedInt(fields[6], 3, 65, "terrain columns");
        if (spacing <= 0.0 || spacing > 32.0 || (long) rows * columns > 4225) {
            throw new ProtocolException("snapshot", "terrain grid spacing or dimensions are invalid");
        }
        int pointCount = rows * columns;
        int boxesIndex = 7 + pointCount;
        if (fields.length <= boxesIndex) throw new ProtocolException("snapshot", "terrain grid is truncated");
        int boxCount = parseBoundedInt(fields[boxesIndex], 0, 256, "collision boxes");
        if (fields.length != boxesIndex + 1 + boxCount) {
            throw new ProtocolException("snapshot", "collision box count does not match the snapshot");
        }

        StringBuilder result = new StringBuilder(Math.min(MAX_LINE_BYTES, 64 + fields.length * 80));
        if (nextCityWorldSequence < 0) throw new ProtocolException("snapshot", "city world sequence exhausted");
        result.append("CITYWORLD\t").append(nextCityWorldSequence++);
        double cityBaseY = CONFIG.transform.toCities(0.0, -64.0, 0.0).y;
        double halfStep = spacing * 0.5;
        for (int row = 0; row < rows; row++) {
            double z = centerZ + (row - (rows - 1) * 0.5) * spacing;
            for (int column = 0; column < columns; column++) {
                double x = centerX + (column - (columns - 1) * 0.5) * spacing;
                double topY = parseFinite(fields[7 + row * columns + column]);
                WorldTransform.Bounds cell = CONFIG.transform.toMinecraftBounds(
                        x - halfStep, cityBaseY, z - halfStep,
                        x + halfStep, topY, z + halfStep);
                appendBox(result, "T", cell);
            }
        }
        for (int i = 0; i < boxCount; i++) {
            String[] values = fields[boxesIndex + 1 + i].split(",", -1);
            if (values.length != 7) throw new ProtocolException("snapshot", "building box needs ID and six bounds");
            parseSequence(values[0]);
            double minX = parseFinite(values[1]);
            double minY = parseFinite(values[2]);
            double minZ = parseFinite(values[3]);
            double maxX = parseFinite(values[4]);
            double maxY = parseFinite(values[5]);
            double maxZ = parseFinite(values[6]);
            if (minX >= maxX || minY >= maxY || minZ >= maxZ) {
                throw new ProtocolException("snapshot", "building bounds must have positive volume");
            }
            appendBox(result, "B", CONFIG.transform.toMinecraftBounds(minX, minY, minZ, maxX, maxY, maxZ));
        }
        if (result.length() > MAX_LINE_BYTES) throw new ProtocolException("snapshot", "mapped city snapshot is too large");
        sender.lastSnapshotSequence = sequence;
        return result.toString();
    }

    private static void appendBox(StringBuilder result, String type, WorldTransform.Bounds bounds) {
        result.append('\t').append(type).append(',').append(bounds.minX).append(',').append(bounds.minY)
                .append(',').append(bounds.minZ).append(',').append(bounds.maxX).append(',')
                .append(bounds.maxY).append(',').append(bounds.maxZ);
    }

    private static int parseBoundedInt(String value, int minimum, int maximum, String name) throws ProtocolException {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException("outside bounds");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ProtocolException("snapshot", name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static long parseSequence(String value) throws ProtocolException {
        try {
            long sequence = Long.parseLong(value);
            if (sequence < 0) throw new NumberFormatException("negative sequence");
            return sequence;
        } catch (NumberFormatException exception) {
            throw new ProtocolException("sequence", "sequence must be a non-negative integer");
        }
    }

    private static double parseFinite(String value) throws ProtocolException {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) throw new NumberFormatException("non-finite value");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ProtocolException("value", "numeric fields must be finite numbers");
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) return bytes.size() == 0 ? null : decode(bytes.toByteArray());
            if (value == '\n') {
                byte[] line = bytes.toByteArray();
                int length = line.length;
                if (length > 0 && line[length - 1] == '\r') length--;
                byte[] trimmed = new byte[length];
                System.arraycopy(line, 0, trimmed, 0, length);
                return decode(trimmed);
            }
            if (bytes.size() == MAX_LINE_BYTES) throw new IOException("line exceeds 4096 bytes");
            bytes.write(value);
        }
    }

    private static String decode(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("invalid UTF-8 line", exception);
        }
    }

    private static void sendError(Socket socket, String code, String message) throws IOException {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        writer.println("ERROR\t" + code + "\t" + message);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (server != null) server.close();
        if (frameServer != null) frameServer.close();
        synchronized (peersLock) {
            closePeer(minecraft);
            closePeer(cities);
            minecraft = null;
            cities = null;
        }
        synchronized (frameLock) {
            closeFramePeer(frameMinecraft);
            closeFramePeer(frameCities);
            frameMinecraft = null;
            frameCities = null;
            frameLock.notifyAll();
        }
        clients.shutdownNow();
    }

    private static void closePeer(Peer peer) {
        if (peer != null) {
            try { peer.socket.close(); } catch (IOException ignored) { }
        }
    }

    private static void closeFramePeer(FramePeer peer) {
        if (peer != null) {
            try { peer.socket.close(); } catch (IOException ignored) { }
        }
    }

    private enum Role {
        MINECRAFT("minecraft"), CITIES("cities");
        final String wireName;
        Role(String wireName) { this.wireName = wireName; }
        static Role parse(String value) {
            for (Role role : values()) if (role.wireName.equals(value)) return role;
            return null;
        }
    }

    private static final class Peer {
        final Role role;
        final Socket socket;
        final PrintWriter writer;
        long lastSequence = -1;
        long lastSnapshotSequence = -1;
        Peer(Role role, Socket socket) throws IOException {
            this.role = role;
            this.socket = socket;
            this.writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }
        synchronized void send(String message) throws IOException {
            writer.println(message);
            if (writer.checkError()) throw new IOException("peer write failed");
        }
    }

    private static final class FramePeer {
        final Role role;
        final Socket socket;
        long lastSequence = -1;
        FramePeer(Role role, Socket socket) {
            this.role = role;
            this.socket = socket;
        }
    }

    private static final class FrameSnapshot {
        final long generation;
        final int width;
        final int height;
        final long sequence;
        final byte[] rgba;
        FrameSnapshot(long generation, int width, int height, long sequence, byte[] rgba) {
            this.generation = generation;
            this.width = width;
            this.height = height;
            this.sequence = sequence;
            this.rgba = rgba;
        }
    }

    private static final class ProtocolException extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;
        ProtocolException(String code, String message) { super(message); this.code = code; }
    }
}
