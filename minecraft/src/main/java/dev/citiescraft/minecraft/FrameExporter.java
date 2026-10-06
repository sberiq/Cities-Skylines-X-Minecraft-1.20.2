package dev.citiescraft.minecraft;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Camera;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

/** Captures Minecraft world, depth, hand, GUI and matching camera pose into CCF3 frames. */
public final class FrameExporter {
    static final int WIDTH = 640;
    static final int HEIGHT = 360;
    private static final int BYTES_PER_PIXEL = 4;
    private static final int FRAME_BYTES = WIDTH * HEIGHT * BYTES_PER_PIXEL;
    public static final int DEPTH_ENCODING_LINEAR_METERS = 1;
    private static final int SLOT_COUNT = 3;
    private static final long FRAME_INTERVAL_NANOS = 100_000_000L;
    private static final long LAYER_FRAME_INTERVAL_NANOS = 50_000_000L;

    private static final AtomicReference<LayerFrame> LATEST_LAYERS = new AtomicReference<>();
    private static final LayerReadbackSlot[] LAYER_SLOTS = new LayerReadbackSlot[SLOT_COUNT];
    private static int layerFramebuffer;
    private static int layerColorTexture;
    private static int layerDepthBuffer;
    private static int compositeProgram;
    private static int compositeVao;
    private static int compositeVbo;
    private static long layerSequence;
    private static long lastLayerCaptureNanos;
    private static long layerWorldEpoch;
    private static LayerReadbackSlot currentLayer;
    private static int layerRestoreFramebuffer;
    private static int layerRestoreReadFramebuffer;
    private static int layerRestoreReadBuffer;
    private static int layerRestoreDrawBuffer;
    private static int layerRestoreViewportX;
    private static int layerRestoreViewportY;
    private static int layerRestoreViewportWidth;
    private static int layerRestoreViewportHeight;
    private static boolean layerResourcesReady;

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

    /**
     * Immutable, same-frame world and transparent-overlay capture. RGBA arrays are top-left-origin,
     * straight-alpha RGBA8. depthMeters is IEEE-754 float32 in big-endian order; pixels with no
     * opaque Minecraft surface contain positive infinity. Arrays are treated as read-only by callers.
     */
    public static final class LayerFrame {
        public final int width;
        public final int height;
        public final long sequence;
        public final long timestampNanos;
        private final long worldEpoch;
        public final int depthEncoding;
        public final float nearPlaneMeters;
        public final float farPlaneMeters;
        public final double cameraX;
        public final double cameraY;
        public final double cameraZ;
        public final float cameraYaw;
        public final float cameraPitch;
        public final float verticalFov;
        public final float aspect;
        public final byte[] worldRgba;
        public final byte[] depthMeters;
        public final byte[] handRgba;
        public final byte[] guiRgba;
        public final byte[] overlayRgba;

        private LayerFrame(int width, int height, long sequence, long timestampNanos, long worldEpoch,
                           int depthEncoding, float nearPlaneMeters, float farPlaneMeters,
                           double cameraX, double cameraY, double cameraZ, float cameraYaw,
                           float cameraPitch, float verticalFov, float aspect,
                           byte[] worldRgba, byte[] depthMeters, byte[] handRgba,
                           byte[] guiRgba, byte[] overlayRgba) {
            this.width = width;
            this.height = height;
            this.sequence = sequence;
            this.timestampNanos = timestampNanos;
            this.worldEpoch = worldEpoch;
            this.depthEncoding = depthEncoding;
            this.nearPlaneMeters = nearPlaneMeters;
            this.farPlaneMeters = farPlaneMeters;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
            this.cameraYaw = cameraYaw;
            this.cameraPitch = cameraPitch;
            this.verticalFov = verticalFov;
            this.aspect = aspect;
            this.worldRgba = worldRgba;
            this.depthMeters = depthMeters;
            this.handRgba = handRgba;
            this.guiRgba = guiRgba;
            this.overlayRgba = overlayRgba;
        }
    }

    /** Returns the newest fully completed atomic frame, or {@code null} until one is ready. */
    public static LayerFrame latestLayers() {
        return LATEST_LAYERS.get();
    }

    public static void start() {
        if (started) return;
        started = true;
        SENDER.start();
    }

    /** Called from GameRenderer.render's TAIL, while Minecraft's GL context is current. */
    public static void afterRender(MinecraftClient client) {
        if (!started || SENDER.isStopping()) return;
        boolean inWorld = syncWorldState(client.world != null);
        drainLayerReadbacks(inWorld, layerWorldEpoch);
    }

    /** Called immediately after WorldRenderer.render and before GameRenderer renders the hand. */
    public static void afterWorldRender(MinecraftClient client) {
        if (!started || SENDER.isStopping() || !syncWorldState(client.world != null)) return;
        long now = System.nanoTime();
        drainLayerReadbacks(true, layerWorldEpoch);
        if (now - lastLayerCaptureNanos < LAYER_FRAME_INTERVAL_NANOS) return;
        if (!layerResourcesReady) {
            try {
                createLayerResources();
                layerResourcesReady = true;
            } catch (RuntimeException exception) {
                System.getLogger(FrameExporter.class.getName()).log(System.Logger.Level.WARNING,
                        "Could not initialize Minecraft layer capture; capture will retry", exception);
                return;
            }
        }
        LayerReadbackSlot slot = findFreeLayerSlot();
        if (slot == null) return;
        Framebuffer source = client.getFramebuffer();
        if (source == null || source.fbo == 0 || source.textureWidth <= 0 || source.textureHeight <= 0) return;
        if (!captureWorldLayer(source, slot)) return;
        Camera camera = client.gameRenderer.getCamera();
        if (camera == null || !camera.isReady()) return;
        net.minecraft.util.math.Vec3d cameraPosition = camera.getPos();
        slot.sequence = layerSequence++;
        slot.timestampNanos = now;
        slot.nearPlaneMeters = 0.05f;
        slot.farPlaneMeters = client.gameRenderer.getFarPlaneDistance();
        slot.cameraX = cameraPosition.x;
        slot.cameraY = cameraPosition.y;
        slot.cameraZ = cameraPosition.z;
        slot.cameraYaw = camera.getYaw();
        slot.cameraPitch = camera.getPitch();
        slot.verticalFov = client.options.getFov().getValue();
        slot.aspect = (float) WIDTH / HEIGHT;
        slot.worldEpoch = layerWorldEpoch;
        slot.handCaptured = false;
        slot.guiCaptured = false;
        slot.inFlight = false;
        currentLayer = slot;
        lastLayerCaptureNanos = now;
        CitiesCraftClient.publishRenderedView(client);
    }

    private static boolean syncWorldState(boolean inWorld) {
        if (inWorld != wasInWorld) {
            wasInWorld = inWorld;
            worldEpoch++;
            layerWorldEpoch++;
            lastLayerCaptureNanos = 0L;
            currentLayer = null;
            LATEST_LAYERS.set(null);
            SENDER.setWorldActive(inWorld, worldEpoch);
        }
        return inWorld;
    }

    /** Redirects the native first-person hand and in-world overlays into a transparent layer. */
    public static void beginHandLayer() {
        if (currentLayer == null || !layerResourcesReady) return;
        beginTransparentLayer();
    }

    /** Captures the hand layer and composites it back so the local Minecraft display stays vanilla. */
    public static void finishHandLayer() {
        if (currentLayer == null || !layerResourcesReady) return;
        captureOverlayPbo(currentLayer.handPbo);
        currentLayer.handCaptured = true;
        compositeLayerToFramebuffer(layerRestoreFramebuffer);
        restoreLayerTarget();
    }

    /** Called just after the main GUI framebuffer is rebound; subsequent HUD/screens draw transparently. */
    public static void beginGuiLayer() {
        if (currentLayer == null || !layerResourcesReady) return;
        beginTransparentLayer();
    }

    /** Captures HUD/screens and composites them back after Minecraft flushes its GUI draw context. */
    public static void finishGuiLayer(Framebuffer mainFramebuffer) {
        if (currentLayer != null && layerResourcesReady) {
            captureOverlayPbo(currentLayer.guiPbo);
            currentLayer.guiCaptured = true;
            compositeLayerToFramebuffer(mainFramebuffer.fbo);
            restoreLayerTarget();
            if (currentLayer.fence != 0L) GL32.glDeleteSync(currentLayer.fence);
            currentLayer.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            currentLayer.inFlight = currentLayer.fence != 0L;
            if (!currentLayer.inFlight) currentLayer = null;
            else currentLayer = null;
        }
    }

    public static void stop() {
        SENDER.stop();
        LATEST_LAYERS.set(null);
        currentLayer = null;
        if (RenderSystem.isOnRenderThread()) {
            deleteResources();
            deleteLayerResources();
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

    private static void createLayerResources() {
        RenderSystem.assertOnRenderThread();
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousRenderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        int previousPixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            layerColorTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, layerColorTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, WIDTH, HEIGHT, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);

            layerFramebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, layerFramebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, layerColorTexture, 0);
            layerDepthBuffer = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, layerDepthBuffer);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, WIDTH, HEIGHT);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_RENDERBUFFER, layerDepthBuffer);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Layer framebuffer is incomplete: 0x" + Integer.toHexString(status));
            }

            for (int i = 0; i < SLOT_COUNT; i++) {
                LayerReadbackSlot slot = new LayerReadbackSlot();
                slot.worldColorPbo = createReadbackBuffer(FRAME_BYTES);
                slot.depthPbo = createReadbackBuffer(FRAME_BYTES);
                slot.handPbo = createReadbackBuffer(FRAME_BYTES);
                slot.guiPbo = createReadbackBuffer(FRAME_BYTES);
                LAYER_SLOTS[i] = slot;
            }
            createCompositeResources();
        } catch (RuntimeException exception) {
            deleteLayerResources();
            throw exception;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previousRenderbuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        }
    }

    private static int createReadbackBuffer(int size) {
        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
        GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) size, GL15.GL_STREAM_READ);
        return buffer;
    }

    private static void createCompositeResources() {
        String vertex = "#version 150\n"
                + "in vec2 Position; in vec2 TexCoord; out vec2 uv;\n"
                + "void main(){ uv=TexCoord; gl_Position=vec4(Position,0.0,1.0); }\n";
        String fragment = "#version 150\n"
                + "uniform sampler2D Layer; in vec2 uv; out vec4 color;\n"
                + "void main(){ color=texture(Layer,uv); }\n";
        int vs = compileShader(GL20.GL_VERTEX_SHADER, vertex);
        int fs = compileShader(GL20.GL_FRAGMENT_SHADER, fragment);
        compositeProgram = GL20.glCreateProgram();
        GL20.glAttachShader(compositeProgram, vs);
        GL20.glAttachShader(compositeProgram, fs);
        GL20.glBindAttribLocation(compositeProgram, 0, "Position");
        GL20.glBindAttribLocation(compositeProgram, 1, "TexCoord");
        GL20.glLinkProgram(compositeProgram);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(compositeProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(compositeProgram);
            GL20.glDeleteProgram(compositeProgram);
            compositeProgram = 0;
            throw new IllegalStateException("Could not link layer compositor: " + log);
        }
        int sampler = GL20.glGetUniformLocation(compositeProgram, "Layer");
        GL20.glUseProgram(compositeProgram);
        GL20.glUniform1i(sampler, 0);
        GL20.glUseProgram(0);

        float[] vertices = {
                -1.0f, -1.0f, 0.0f, 0.0f,
                 3.0f, -1.0f, 2.0f, 0.0f,
                -1.0f,  3.0f, 0.0f, 2.0f
        };
        ByteBuffer vertexBytes = ByteBuffer.allocateDirect(vertices.length * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (float value : vertices) vertexBytes.putFloat(value);
        vertexBytes.flip();
        compositeVao = GL30.glGenVertexArrays();
        compositeVbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(compositeVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, compositeVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBytes, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Could not compile layer compositor shader: " + log);
        }
        return shader;
    }

    private static boolean captureWorldLayer(Framebuffer source, LayerReadbackSlot slot) {
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
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, layerFramebuffer);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBlitFramebuffer(0, 0, source.textureWidth, source.textureHeight,
                    0, 0, WIDTH, HEIGHT, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            GL30.glBlitFramebuffer(0, 0, source.textureWidth, source.textureHeight,
                    0, 0, WIDTH, HEIGHT, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, layerFramebuffer);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.worldColorPbo);
            GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.depthPbo);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 0L);
            return true;
        } catch (RuntimeException exception) {
            System.getLogger(FrameExporter.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not capture Minecraft world color/depth", exception);
            return false;
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

    private static void beginTransparentLayer() {
        RenderSystem.assertOnRenderThread();
        layerRestoreFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        layerRestoreReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        layerRestoreReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        layerRestoreDrawBuffer = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        layerRestoreViewportX = viewport[0];
        layerRestoreViewportY = viewport[1];
        layerRestoreViewportWidth = viewport[2];
        layerRestoreViewportHeight = viewport[3];
        boolean previousScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        ByteBuffer clearColorBytes = ByteBuffer.allocateDirect(4 * Float.BYTES).order(ByteOrder.nativeOrder());
        java.nio.FloatBuffer clearColor = clearColorBytes.asFloatBuffer();
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        double previousDepthClear = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        boolean previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        ByteBuffer previousColorMask = ByteBuffer.allocateDirect(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, previousColorMask);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, layerFramebuffer);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glViewport(0, 0, WIDTH, HEIGHT);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GL11.glClearDepth(1.0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glClearColor(clearColor.get(0), clearColor.get(1), clearColor.get(2), clearColor.get(3));
        GL11.glClearDepth(previousDepthClear);
        GL11.glDepthMask(previousDepthMask);
        GL11.glColorMask(previousColorMask.get(0) != 0, previousColorMask.get(1) != 0,
                previousColorMask.get(2) != 0, previousColorMask.get(3) != 0);
        if (previousScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
    }

    private static void captureOverlayPbo(int pbo) {
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousPixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int previousPackRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int previousPackSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int previousPackSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, layerFramebuffer);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousPackAlignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, previousPackRowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, previousPackSkipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, previousPackSkipPixels);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL11.glReadBuffer(previousReadBuffer);
        }
    }

    private static void compositeLayerToFramebuffer(int targetFramebuffer) {
        if (targetFramebuffer == 0) return;
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int previousTexture0;
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousViewport[] = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);
        boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int[] colorMask = new int[4];
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorMask);
        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, targetFramebuffer);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            Framebuffer main = MinecraftClient.getInstance().getFramebuffer();
            GL11.glViewport(0, 0, main.textureWidth, main.textureHeight);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            // The transparent FBO stores premultiplied RGB; use ONE for its source color.
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL20.glUseProgram(compositeProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, layerColorTexture);
            GL30.glBindVertexArray(compositeVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } finally {
            GL30.glBindVertexArray(previousVao);
            GL20.glUseProgram(previousProgram);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture0);
            GL13.glActiveTexture(previousActiveTexture);
            GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL11.glDepthMask(previousDepthMask);
            GL11.glColorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
            setEnabled(GL11.GL_BLEND, blendWasEnabled);
            setEnabled(GL11.GL_DEPTH_TEST, depthWasEnabled);
            setEnabled(GL11.GL_CULL_FACE, cullWasEnabled);
            GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        }
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability); else GL11.glDisable(capability);
    }

    private static void restoreLayerTarget() {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, layerRestoreReadFramebuffer);
        GL11.glReadBuffer(layerRestoreReadBuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, layerRestoreFramebuffer);
        GL11.glDrawBuffer(layerRestoreDrawBuffer);
        GL11.glViewport(layerRestoreViewportX, layerRestoreViewportY,
                layerRestoreViewportWidth, layerRestoreViewportHeight);
    }

    private static LayerReadbackSlot findFreeLayerSlot() {
        for (LayerReadbackSlot slot : LAYER_SLOTS) {
            if (slot != null && !slot.inFlight && slot != currentLayer) return slot;
        }
        return null;
    }

    private static void drainLayerReadbacks(boolean inWorld, long currentEpoch) {
        if (!layerResourcesReady) return;
        RenderSystem.assertOnRenderThread();
        int previousPixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            for (LayerReadbackSlot slot : LAYER_SLOTS) {
                if (slot == null || !slot.inFlight) continue;
                int status = GL32.glClientWaitSync(slot.fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
                if (status == GL32.GL_TIMEOUT_EXPIRED) continue;
                GL32.glDeleteSync(slot.fence);
                slot.fence = 0L;
                slot.inFlight = false;
                if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) continue;
                if (!inWorld || slot.worldEpoch != currentEpoch) continue;

                byte[] world = readPbo(slot.worldColorPbo);
                byte[] depthRaw = readPbo(slot.depthPbo);
                byte[] hand = slot.handCaptured ? readPbo(slot.handPbo) : new byte[FRAME_BYTES];
                byte[] gui = slot.guiCaptured ? readPbo(slot.guiPbo) : new byte[FRAME_BYTES];
                if (world == null || depthRaw == null || hand == null || gui == null) continue;
                flipRowsInPlace(world, WIDTH, HEIGHT);
                flipRowsInPlace(hand, WIDTH, HEIGHT);
                flipRowsInPlace(gui, WIDTH, HEIGHT);
                unpremultiply(hand);
                unpremultiply(gui);
                byte[] overlay = mergeOverlays(hand, gui);
                byte[] depthMeters = linearizeDepth(depthRaw, WIDTH, HEIGHT,
                        slot.nearPlaneMeters, slot.farPlaneMeters);
                LayerFrame frame = new LayerFrame(WIDTH, HEIGHT, slot.sequence, slot.timestampNanos,
                        slot.worldEpoch,
                        DEPTH_ENCODING_LINEAR_METERS, slot.nearPlaneMeters, slot.farPlaneMeters,
                        slot.cameraX, slot.cameraY, slot.cameraZ, slot.cameraYaw, slot.cameraPitch,
                        slot.verticalFov, slot.aspect,
                        world, depthMeters, hand, gui, overlay);
                LATEST_LAYERS.set(frame);
                SENDER.offer(frame);
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
        }
    }

    private static byte[] readPbo(int pbo) {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
        ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
        if (mapped == null || mapped.remaining() < FRAME_BYTES) {
            if (mapped != null) GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            return null;
        }
        byte[] bytes = new byte[FRAME_BYTES];
        mapped.get(bytes);
        if (!GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER)) return null;
        return bytes;
    }

    private static byte[] linearizeDepth(byte[] raw, int width, int height, float near, float far) {
        ByteBuffer source = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder());
        ByteBuffer output = ByteBuffer.allocate(raw.length).order(ByteOrder.BIG_ENDIAN);
        for (int y = 0; y < height; y++) {
            int sourceY = height - 1 - y;
            for (int x = 0; x < width; x++) {
                float depth = source.getFloat((sourceY * width + x) * Float.BYTES);
                float distance = depth >= 0.9999999f ? Float.POSITIVE_INFINITY
                        : near * far / (far - depth * (far - near));
                output.putFloat(distance);
            }
        }
        return output.array();
    }

    private static void unpremultiply(byte[] rgba) {
        for (int i = 0; i < rgba.length; i += 4) {
            int alpha = rgba[i + 3] & 0xff;
            if (alpha == 0) {
                rgba[i] = rgba[i + 1] = rgba[i + 2] = 0;
                continue;
            }
            for (int channel = 0; channel < 3; channel++) {
                int value = rgba[i + channel] & 0xff;
                rgba[i + channel] = (byte) Math.min(255, (value * 255 + alpha / 2) / alpha);
            }
        }
    }

    private static byte[] mergeOverlays(byte[] hand, byte[] gui) {
        byte[] merged = new byte[FRAME_BYTES];
        for (int i = 0; i < FRAME_BYTES; i += 4) {
            float handAlpha = (hand[i + 3] & 0xff) / 255.0f;
            float guiAlpha = (gui[i + 3] & 0xff) / 255.0f;
            float outputAlpha = guiAlpha + handAlpha * (1.0f - guiAlpha);
            if (outputAlpha <= 0.0f) continue;
            for (int channel = 0; channel < 3; channel++) {
                float handColor = (hand[i + channel] & 0xff) / 255.0f;
                float guiColor = (gui[i + channel] & 0xff) / 255.0f;
                float out = (guiColor * guiAlpha + handColor * handAlpha * (1.0f - guiAlpha)) / outputAlpha;
                merged[i + channel] = (byte) Math.round(out * 255.0f);
            }
            merged[i + 3] = (byte) Math.round(outputAlpha * 255.0f);
        }
        return merged;
    }

    private static void flipRowsInPlace(byte[] pixels, int width, int height) {
        int stride = width * BYTES_PER_PIXEL;
        byte[] row = new byte[stride];
        for (int y = 0; y < height / 2; y++) {
            int top = y * stride;
            int bottom = (height - 1 - y) * stride;
            System.arraycopy(pixels, top, row, 0, stride);
            System.arraycopy(pixels, bottom, pixels, top, stride);
            System.arraycopy(row, 0, pixels, bottom, stride);
        }
    }

    private static void deleteLayerResources() {
        if (RenderSystem.isOnRenderThread()) {
            for (LayerReadbackSlot slot : LAYER_SLOTS) {
                if (slot == null) continue;
                if (slot.fence != 0L) GL32.glDeleteSync(slot.fence);
                if (slot.worldColorPbo != 0) GL15.glDeleteBuffers(slot.worldColorPbo);
                if (slot.depthPbo != 0) GL15.glDeleteBuffers(slot.depthPbo);
                if (slot.handPbo != 0) GL15.glDeleteBuffers(slot.handPbo);
                if (slot.guiPbo != 0) GL15.glDeleteBuffers(slot.guiPbo);
            }
            if (layerFramebuffer != 0) GL30.glDeleteFramebuffers(layerFramebuffer);
            if (layerColorTexture != 0) GL11.glDeleteTextures(layerColorTexture);
            if (layerDepthBuffer != 0) GL30.glDeleteRenderbuffers(layerDepthBuffer);
            if (compositeVbo != 0) GL15.glDeleteBuffers(compositeVbo);
            if (compositeVao != 0) GL30.glDeleteVertexArrays(compositeVao);
            if (compositeProgram != 0) GL20.glDeleteProgram(compositeProgram);
        }
        for (int i = 0; i < LAYER_SLOTS.length; i++) LAYER_SLOTS[i] = null;
        layerFramebuffer = layerColorTexture = layerDepthBuffer = compositeProgram = compositeVao = compositeVbo = 0;
        layerResourcesReady = false;
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

    private static final class LayerReadbackSlot {
        int worldColorPbo;
        int depthPbo;
        int handPbo;
        int guiPbo;
        long fence;
        long sequence;
        long timestampNanos;
        long worldEpoch;
        float nearPlaneMeters;
        float farPlaneMeters;
        double cameraX;
        double cameraY;
        double cameraZ;
        float cameraYaw;
        float cameraPitch;
        float verticalFov;
        float aspect;
        boolean handCaptured;
        boolean guiCaptured;
        boolean inFlight;
    }

    private static final class FrameSender implements Runnable {
        private final Object queueLock = new Object();
        private final AtomicReference<LayerFrame> latest = new AtomicReference<>();
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

        void offer(LayerFrame frame) {
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
                    bufferedOutput.write("CCFRAME/3\tminecraft\n".getBytes(StandardCharsets.US_ASCII));
                    bufferedOutput.flush();
                    readHandshake(socket.getInputStream());
                    socket.setSoTimeout(0);

                    DataOutputStream output = new DataOutputStream(bufferedOutput);
                    while (!stopping && worldActive) {
                        LayerFrame frame = takeLatest();
                        if (frame == null || frame.worldEpoch != worldEpoch || !worldActive) continue;
                        output.writeInt(0x43434633); // CCF3
                        output.writeInt(frame.width);
                        output.writeInt(frame.height);
                        output.writeLong(frame.sequence);
                        output.writeLong(frame.timestampNanos);
                        output.writeInt(frame.worldRgba.length);
                        output.writeInt(frame.depthMeters.length);
                        output.writeInt(frame.handRgba.length);
                        output.writeInt(frame.guiRgba.length);
                        output.writeInt(frame.depthEncoding);
                        output.writeFloat(frame.nearPlaneMeters);
                        output.writeFloat(frame.farPlaneMeters);
                        output.writeDouble(frame.cameraX);
                        output.writeDouble(frame.cameraY);
                        output.writeDouble(frame.cameraZ);
                        output.writeFloat(frame.cameraYaw);
                        output.writeFloat(frame.cameraPitch);
                        output.writeFloat(frame.verticalFov);
                        output.writeFloat(frame.aspect);
                        output.write(frame.worldRgba);
                        output.write(frame.depthMeters);
                        output.write(frame.handRgba);
                        output.write(frame.guiRgba);
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
            byte[] expected = "CCFRAME/3\tOK".getBytes(StandardCharsets.US_ASCII);
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

        private LayerFrame takeLatest() {
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
