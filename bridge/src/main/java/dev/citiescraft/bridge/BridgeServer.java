package dev.citiescraft.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

/** Low-rate localhost state relay. Pixel data will use a separate IPC path. */
public final class BridgeServer implements AutoCloseable {
    private static final int PORT = Integer.getInteger("citiescraft.port", 25598);
    private static final int MAX_LINE_BYTES = 4096;

    private final Object peersLock = new Object();
    private final ExecutorService clients = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "citiescraft-bridge-client");
        thread.setDaemon(true);
        return thread;
    });
    private Peer minecraft;
    private Peer cities;
    private String latestPlayer;
    private String latestCamera;
    private volatile boolean closed;
    private ServerSocket server;

    public static void main(String[] args) throws IOException {
        try (BridgeServer bridge = new BridgeServer()) {
            bridge.serve();
        }
    }

    private void serve() throws IOException {
        server = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
        System.out.println("CitiesCraft Bridge listening on 127.0.0.1:" + PORT);
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
                String latest = role == Role.MINECRAFT ? latestCamera : latestPlayer;
                if (latest != null) peer.send(latest);
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
            synchronized (peersLock) {
                latestPlayer = line;
                if (cities != null) cities.send(line);
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
            synchronized (peersLock) {
                latestCamera = line;
                if (minecraft != null) minecraft.send(line);
            }
            return;
        }

        throw new ProtocolException("message", "message is malformed or not allowed for this role");
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
        synchronized (peersLock) {
            closePeer(minecraft);
            closePeer(cities);
            minecraft = null;
            cities = null;
        }
        clients.shutdownNow();
    }

    private static void closePeer(Peer peer) {
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

    private static final class ProtocolException extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;
        ProtocolException(String code, String message) { super(message); this.code = code; }
    }
}
