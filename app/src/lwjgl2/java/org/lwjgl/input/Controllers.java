package org.lwjgl.input;

import org.lwjgl.LWJGLException;

/**
 * Gamepads, which LWJGL 2 read through jinput.
 *
 * <p>jinput is dropped from the classpath — it is native, x86-only and reads {@code /dev/input}
 * directly, none of which survives the move to Android — so this reports that no controller was
 * found. That is a state LWJGL 2 itself could be in, and the games that touch this only ever
 * iterate the list.
 *
 * <p>Gamepad support, when it comes, belongs in the touch overlay alongside the on-screen controls,
 * where it can reach Android's own {@code InputDevice} API and feed the same GLFW events.
 */
public class Controllers {

    private static boolean created;

    public static void create() throws LWJGLException {
        created = true;
    }

    public static boolean isCreated() {
        return created;
    }

    public static void destroy() {
        created = false;
    }

    public static int getControllerCount() {
        return 0;
    }

    public static void poll() {
    }

    public static void clearEvents() {
    }

    public static boolean next() {
        return false;
    }
}
