package dev.citiescraft.minecraft;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

/** Captures a downscaled final client frame and forwards it to the local frame receiver. */
public final class FrameExporter {
    static final int WIDTH = 640;
    static final int HEIGHT = 360;
    private static final int BYTES_PER_PIXEL = 4;
    private static final int FRAME_BYTES = WIDTH * HEIGHT * BYTES_PER_PIXEL;
    private static final int SLOT_COUNT = 3;
    private static final long FRAME_INTERVAL_NANOS = 100_000_000L;

    private static final FrameSender SENDER = new FrameSender();
    private static final ReadbackSlot[] SLOTS = new ReadbackSlot[SLOT_COUNT];
    private static int captureFramebuffer;
    private static int captureTexture;
    private static long sequence;
    private static long worldEpoch;
    private static long lastCaptureNanos;
    private static boolean wasInWorld;
    private static boolean started;
    private static boolean resourcesReady;

    private FrameExporter() { }

    public static void start() {
        if (started) return;
        started = true;
        SENDER.start();
    }

    /** Called from GameRenderer.render's TAIL, while Minecraft's GL context is current. */
    public static void afterRender(MinecraftClient client) {
        if (!started || SENDER.isStopping()) return;

        boolean inWorld = client.world != null;
        if (inWorld != wasInWorld) {
            wasInWorld = inWorld;
            worldEpoch++;
            lastCaptureNanos = 0L;
            SENDER.setWorldActive(inWorld, worldEpoch);
        }

        if (!resourcesReady) {
            try {
                createResources();
                resourcesReady = true;
            } catch (RuntimeException exception) {
                System.getLogger(FrameExporter.class.getName()).log(System.Logger.Level.WARNING,
                        "Could not initialize Minecraft frame readback; capture will retry", exception);
                return;
            }
        }

        drainCompletedReadbacks(inWorld, worldEpoch);
        if (!inWorld) return;

        long now = System.nanoTime();
        if (now - lastCaptureNanos < FRAME_INTERVAL_NANOS) return;

        ReadbackSlot slot = findFreeSlot();
        if (slot == null) return;

        Framebuffer source = client.getFramebuffer();
        if (source == null || source.fbo == 0 || source.textureWidth <= 0 || source.textureHeight <= 0) return;

        if (capture(source, slot)) {
            slot.sequence = sequence++;
            slot.worldEpoch = worldEpoch;
            lastCaptureNanos = now;
        }
    }

    public static void stop() {
        SENDER.stop();
        if (RenderSystem.isOnRenderThread()) {
            deleteResources();
        }
    }

    private static ReadbackSlot findFreeSlot() {
        for (ReadbackSlot slot : SLOTS) {
            if (slot != null && !slot.inFlight) return slot;
        }
        return null;
    }

    private static void createResources() {
        RenderSystem.assertOnRenderThread();

        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousPixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousPixelUnpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousDrawBuffer = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0);

        try {
            captureTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, captureTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, WIDTH, HEIGHT, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);

            captureFramebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, captureFramebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, captureTexture, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Capture framebuffer is incomplete: 0x" + Integer.toHexString(status));
            }

            for (int i = 0; i < SLOT_COUNT; i++) {
                ReadbackSlot slot = new ReadbackSlot();
                slot.buffer = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.buffer);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) FRAME_BYTES, GL15.GL_STREAM_READ);
                SLOTS[i] = slot;
            }
        } catch (RuntimeException exception) {
            deleteResources();
            throw exception;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, previousPixelUnpackBuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL11.glReadBuffer(previousReadBuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL11.glDrawBuffer(previousDrawBuffer);
        }
    }

    private static boolean capture(Framebuffer source, ReadbackSlot slot) {
        RenderSystem.assertOnRenderThread();

        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousPixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousDrawBuffer = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0);
        int previousPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int previousPackRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int previousPackSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int previousPackSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);

        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.fbo);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, captureFramebuffer);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBlitFramebuffer(0, 0, source.textureWidth, source.textureHeight,
                    0, 0, WIDTH, HEIGHT, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, captureFramebuffer);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.buffer);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);

            slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (slot.fence == 0L) return false;
            slot.inFlight = true;
            return true;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousPackAlignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, previousPackRowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, previousPackSkipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, previousPackSkipPixels);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL11.glReadBuffer(previousReadBuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL11.glDrawBuffer(previousDrawBuffer);
        }
    }

    private static void drainCompletedReadbacks(boolean inWorld, long currentEpoch) {
        RenderSystem.assertOnRenderThread();
        int previousPixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            for (ReadbackSlot slot : SLOTS) {
                if (slot == null || !slot.inFlight) continue;

                int status = GL32.glClientWaitSync(slot.fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
                if (status == GL32.GL_TIMEOUT_EXPIRED) continue;

                GL32.glDeleteSync(slot.fence);
                slot.fence = 0L;
                slot.inFlight = false;
                if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) continue;
                if (!inWorld || slot.worldEpoch != currentEpoch) continue;

                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.buffer);
                ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
                if (mapped == null || mapped.remaining() < FRAME_BYTES) {
                    if (mapped != null) GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                    continue;
                }

                byte[] pixels = new byte[FRAME_BYTES];
                mapped.get(pixels);
                boolean intact = GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                if (!intact) continue;

                flipRowsInPlace(pixels);
                SENDER.offer(new CapturedFrame(slot.sequence, slot.worldEpoch, pixels));
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
        }
    }

    private static void flipRowsInPlace(byte[] pixels) {
        int stride = WIDTH * BYTES_PER_PIXEL;
        byte[] row = new byte[stride];
        for (int y = 0; y < HEIGHT / 2; y++) {
            int top = y * stride;
            int bottom = (HEIGHT - 1 - y) * stride;
            System.arraycopy(pixels, top, row, 0, stride);
            System.arraycopy(pixels, bottom, pixels, top, stride);
            System.arraycopy(row, 0, pixels, bottom, stride);
        }
    }

    private static void deleteResources() {
        if (!resourcesReady && captureFramebuffer == 0 && captureTexture == 0) return;
        RenderSystem.assertOnRenderThread();
        for (ReadbackSlot slot : SLOTS) {
            if (slot == null) continue;
            if (slot.fence != 0L) GL32.glDeleteSync(slot.fence);
            if (slot.buffer != 0) GL15.glDeleteBuffers(slot.buffer);
        }
        if (captureFramebuffer != 0) GL30.glDeleteFramebuffers(captureFramebuffer);
        if (captureTexture != 0) GL11.glDeleteTextures(captureTexture);
        for (int i = 0; i < SLOTS.length; i++) SLOTS[i] = null;
        captureFramebuffer = 0;
        captureTexture = 0;
        resourcesReady = false;
    }

    private static final class ReadbackSlot {
        int buffer;
        long fence;
        long sequence;
        long worldEpoch;
        boolean inFlight;
    }

    private static final class CapturedFrame {
        final long sequence;
        final long worldEpoch;
        final byte[] pixels;

        CapturedFrame(long sequence, long worldEpoch, byte[] pixels) {
            this.sequence = sequence;
            this.worldEpoch = worldEpoch;
            this.pixels = pixels;
        }
    }

    private static final class FrameSender implements Runnable {
        private final Object queueLock = new Object();
        private final AtomicReference<CapturedFrame> latest = new AtomicReference<>();
        private volatile boolean stopping;
        private volatile boolean worldActive;
        private volatile long worldEpoch;
        private volatile Socket activeSocket;
        private Thread worker;

        void start() {
            worker = new Thread(this, "citiescraft-frame-sender");
            worker.setDaemon(true);
            worker.start();
        }

        boolean isStopping() { return stopping; }

        void setWorldActive(boolean active, long epoch) {
            synchronized (queueLock) {
                worldEpoch = epoch;
                worldActive = active;
                if (!active) latest.set(null);
                queueLock.notifyAll();
            }
        }

        void offer(CapturedFrame frame) {
            synchronized (queueLock) {
                if (stopping || !worldActive || frame.worldEpoch != worldEpoch) return;
                latest.set(frame); // Replacing the one queued frame drops stale frames.
                queueLock.notifyAll();
            }
        }

        void stop() {
            stopping = true;
            Socket socket = activeSocket;
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) { }
            }
            synchronized (queueLock) {
                latest.set(null);
                queueLock.notifyAll();
            }
            if (worker != null) worker.interrupt();
        }

        @Override
        public void run() {
            while (!stopping) {
                waitUntilWorldActive();
                if (stopping) break;
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", 25599), 1000);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(2000);
                    activeSocket = socket;

                    BufferedOutputStream bufferedOutput = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
                    bufferedOutput.write("CCFRAME/1\tminecraft\n".getBytes(StandardCharsets.US_ASCII));
                    bufferedOutput.flush();
                    readHandshake(socket.getInputStream());
                    socket.setSoTimeout(0);

                    DataOutputStream output = new DataOutputStream(bufferedOutput);
                    while (!stopping && worldActive) {
                        CapturedFrame frame = takeLatest();
                        if (frame == null || frame.worldEpoch != worldEpoch || !worldActive) continue;
                        output.writeInt(0x43434631); // CCF1
                        output.writeInt(WIDTH);
                        output.writeInt(HEIGHT);
                        output.writeLong(frame.sequence);
                        output.writeInt(frame.pixels.length);
                        output.write(frame.pixels);
                        output.flush();
                    }
                } catch (IOException exception) {
                    // The companion receiver may start later; retry while in a world.
                } finally {
                    activeSocket = null;
                }
                waitBeforeReconnect();
            }
        }

        private void readHandshake(InputStream stream) throws IOException {
            byte[] expected = "CCFRAME/1\tOK".getBytes(StandardCharsets.US_ASCII);
            int length = 0;
            for (;;) {
                int next = stream.read();
                if (next < 0) throw new IOException("Frame receiver closed before handshake completed");
                if (next == '\n') break;
                if (next > 0x7f || length >= expected.length || next != (expected[length] & 0xff)) {
                    throw new IOException("Unexpected frame receiver handshake");
                }
                length++;
            }
            if (length != expected.length) throw new IOException("Incomplete frame receiver handshake");
        }

        private CapturedFrame takeLatest() {
            synchronized (queueLock) {
                while (!stopping && worldActive && latest.get() == null) {
                    try { queueLock.wait(); }
                    catch (InterruptedException ignored) {
                        if (stopping) return null;
                    }
                }
                return latest.getAndSet(null);
            }
        }

        private void waitUntilWorldActive() {
            synchronized (queueLock) {
                while (!stopping && !worldActive) {
                    try { queueLock.wait(); }
                    catch (InterruptedException ignored) {
                        if (stopping) return;
                    }
                }
            }
        }

        private void waitBeforeReconnect() {
            if (stopping || !worldActive) return;
            try { Thread.sleep(500L); }
            catch (InterruptedException ignored) { }
        }
    }
}
