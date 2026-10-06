package dev.citiescraft.minecraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.Window;
import dev.citiescraft.minecraft.mixin.KeyboardInputInvoker;
import dev.citiescraft.minecraft.mixin.MouseInputInvoker;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates and queues input received from the Cities host. The socket reader calls
 * {@link #accept(String[])}; {@link #drain(MinecraftClient)} replays callbacks on
 * Minecraft's client thread.
 *
 * <p>Wire forms (all fields are tab-separated):
 * <pre>
 * INPUT seq KEY key scanCode action mods
 * INPUT seq CURSOR x01 y01
 * INPUT seq LOOK deltaX deltaY
 * INPUT seq BUTTON button action mods
 * INPUT seq SCROLL horizontal vertical
 * INPUT seq TEXT codepoint mods
 * </pre>
 * KEY uses GLFW key/scancode/action/modifier values. Cursor coordinates are normalized
 * to the embedded Minecraft viewport. LOOK is a relative raw mouse delta in viewport
 * pixels; it remains separate because Minecraft suppresses normal camera mouse motion
 * while its own window is unfocused.
 */
public final class RemoteInputState {
    private static final Logger LOGGER = LoggerFactory.getLogger("CitiesCraft/RemoteInput");
    private static final int MAX_QUEUED_EVENTS = 4096;
    private static final Queue<InputEvent> QUEUE = new ArrayDeque<>();

    private static long lastSequence = -1L;
    private static boolean releaseInputsBeforeNextDrain;

    private RemoteInputState() { }

    /**
     * Accepts one decoded Bridge protocol line. The returned false means the message
     * was malformed, duplicated/out of order, or could not be queued.
     */
    public static synchronized boolean accept(String[] fields) {
        InputEvent event;
        try {
            event = parse(fields);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        if (event.sequence <= lastSequence) return false;
        lastSequence = event.sequence;
        if (QUEUE.size() >= MAX_QUEUED_EVENTS) {
            QUEUE.clear();
            releaseInputsBeforeNextDrain = true;
        }
        QUEUE.add(event);
        return true;
    }

    /** Convenience entry point for callers that hold the un-split protocol line. */
    public static boolean acceptLine(String line) {
        if (line == null) return false;
        return accept(line.split("\\t", -1));
    }

    /** Reset sequence tracking at a new Bridge session and release any held input. */
    public static synchronized void beginSession() {
        QUEUE.clear();
        lastSequence = -1L;
        releaseInputsBeforeNextDrain = true;
    }

    /** Called from the MinecraftClient.tick mixin; dispatch is always client-thread-only. */
    public static void drain(MinecraftClient client) {
        if (client == null) return;
        if (!client.isOnThread()) {
            client.execute(() -> drain(client));
            return;
        }

        List<InputEvent> pending;
        boolean releaseInputs;
        synchronized (RemoteInputState.class) {
            releaseInputs = releaseInputsBeforeNextDrain;
            releaseInputsBeforeNextDrain = false;
            if (QUEUE.isEmpty()) pending = java.util.Collections.emptyList();
            else {
                pending = new ArrayList<>(QUEUE);
                QUEUE.clear();
            }
        }

        if (releaseInputs) releaseAll(client);
        for (InputEvent event : pending) {
            try {
                dispatch(client, event);
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not replay remote {} input event", event.type.name().toLowerCase(Locale.ROOT), exception);
            }
        }
    }

    private static InputEvent parse(String[] fields) {
        if (fields == null || fields.length < 3 || !"INPUT".equals(fields[0])) {
            throw new IllegalArgumentException("not an INPUT line");
        }
        long sequence = parseLong(fields[1], 0L, Long.MAX_VALUE);
        Type type;
        try {
            type = Type.valueOf(fields[2]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown input type", exception);
        }

        switch (type) {
            case KEY:
                requireLength(fields, 7);
                return InputEvent.key(sequence,
                        parseInt(fields[3], -1, GLFW.GLFW_KEY_LAST),
                        parseInt(fields[4], 0, 65535),
                        parseInt(fields[5], GLFW.GLFW_RELEASE, GLFW.GLFW_REPEAT),
                        parseInt(fields[6], 0, 0xFFFF));
            case CURSOR:
                requireLength(fields, 5);
                return InputEvent.cursor(sequence, parseDouble(fields[3], 0.0, 1.0), parseDouble(fields[4], 0.0, 1.0));
            case LOOK:
                requireLength(fields, 5);
                return InputEvent.look(sequence, parseDouble(fields[3], -8192.0, 8192.0), parseDouble(fields[4], -8192.0, 8192.0));
            case BUTTON:
                requireLength(fields, 6);
                return InputEvent.button(sequence,
                        parseInt(fields[3], 0, GLFW.GLFW_MOUSE_BUTTON_LAST),
                        parseInt(fields[4], GLFW.GLFW_RELEASE, GLFW.GLFW_PRESS),
                        parseInt(fields[5], 0, 0xFFFF));
            case SCROLL:
                requireLength(fields, 5);
                return InputEvent.scroll(sequence, parseDouble(fields[3], -64.0, 64.0), parseDouble(fields[4], -64.0, 64.0));
            case TEXT:
                requireLength(fields, 5);
                return InputEvent.text(sequence, parseInt(fields[3], 0, Character.MAX_CODE_POINT), parseInt(fields[4], 0, 0xFFFF));
            default:
                throw new IllegalArgumentException("unsupported input type");
        }
    }

    private static void dispatch(MinecraftClient client, InputEvent event) {
        long window = client.getWindow().getHandle();
        switch (event.type) {
            case KEY:
                client.keyboard.onKey(window, event.a, event.b, event.c, event.d);
                break;
            case CURSOR:
                Window mcWindow = client.getWindow();
                double x = Math.min(mcWindow.getWidth() - 1.0, event.x * mcWindow.getWidth());
                double y = Math.min(mcWindow.getHeight() - 1.0, event.y * mcWindow.getHeight());
                ((MouseInputInvoker) client.mouse).citiescraft$onCursorPos(window, x, y);
                break;
            case LOOK:
                if (client.currentScreen == null && client.player != null) {
                    double sensitivity = client.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
                    double scale = sensitivity * sensitivity * sensitivity * 8.0;
                    double invertY = client.options.getInvertYMouse().getValue() ? -1.0 : 1.0;
                    client.player.changeLookDirection(event.x * scale, event.y * scale * invertY);
                }
                break;
            case BUTTON:
                ((MouseInputInvoker) client.mouse).citiescraft$onMouseButton(window, event.a, event.b, event.c);
                break;
            case SCROLL:
                ((MouseInputInvoker) client.mouse).citiescraft$onMouseScroll(window, event.x, event.y);
                break;
            case TEXT:
                ((KeyboardInputInvoker) client.keyboard).citiescraft$onChar(window, event.a, event.b);
                break;
            default:
                throw new IllegalStateException("unhandled input type: " + event.type);
        }
    }

    private static void releaseAll(MinecraftClient client) {
        KeyBinding.unpressAll();
        long window = client.getWindow().getHandle();
        MouseInputInvoker mouse = (MouseInputInvoker) client.mouse;
        for (int button = 0; button <= GLFW.GLFW_MOUSE_BUTTON_LAST; button++) {
            mouse.citiescraft$onMouseButton(window, button, GLFW.GLFW_RELEASE, 0);
        }
    }

    private static void requireLength(String[] fields, int expected) {
        if (fields.length != expected) throw new IllegalArgumentException("wrong number of input fields");
    }

    private static int parseInt(String text, int minimum, int maximum) {
        int value = Integer.parseInt(text);
        if (value < minimum || value > maximum) throw new IllegalArgumentException("input integer out of range");
        return value;
    }

    private static long parseLong(String text, long minimum, long maximum) {
        long value = Long.parseLong(text);
        if (value < minimum || value > maximum) throw new IllegalArgumentException("input sequence out of range");
        return value;
    }

    private static double parseDouble(String text, double minimum, double maximum) {
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException("input number out of range");
        }
        return value;
    }

    private enum Type { KEY, CURSOR, LOOK, BUTTON, SCROLL, TEXT }

    private static final class InputEvent {
        final long sequence;
        final Type type;
        final int a, b, c, d;
        final double x, y;

        private InputEvent(long sequence, Type type, int a, int b, int c, int d, double x, double y) {
            this.sequence = sequence;
            this.type = type;
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.x = x;
            this.y = y;
        }

        static InputEvent key(long sequence, int key, int scan, int action, int mods) {
            return new InputEvent(sequence, Type.KEY, key, scan, action, mods, 0, 0);
        }
        static InputEvent cursor(long sequence, double x, double y) {
            return new InputEvent(sequence, Type.CURSOR, 0, 0, 0, 0, x, y);
        }
        static InputEvent look(long sequence, double x, double y) {
            return new InputEvent(sequence, Type.LOOK, 0, 0, 0, 0, x, y);
        }
        static InputEvent button(long sequence, int button, int action, int mods) {
            return new InputEvent(sequence, Type.BUTTON, button, action, mods, 0, 0, 0);
        }
        static InputEvent scroll(long sequence, double horizontal, double vertical) {
            return new InputEvent(sequence, Type.SCROLL, 0, 0, 0, 0, horizontal, vertical);
        }
        static InputEvent text(long sequence, int codepoint, int mods) {
            return new InputEvent(sequence, Type.TEXT, codepoint, mods, 0, 0, 0, 0);
        }
    }
}
