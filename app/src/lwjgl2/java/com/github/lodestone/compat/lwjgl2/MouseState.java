package com.github.lodestone.compat.lwjgl2;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Turns GLFW's pointer callbacks into the event queue LWJGL 2's {@code Mouse} reads.
 *
 * <p>Three differences between the two APIs are worth naming, because each of them looks like a
 * physics bug rather than a conversion bug when it is wrong:
 *
 * <ol>
 *   <li><b>The Y axis is upside down.</b> LWJGL 2 puts the origin at the bottom left of the window,
 *       GLFW at the top left. Every position and every vertical delta is flipped.
 *   <li><b>The wheel is scaled.</b> LWJGL 2 inherited Win32's {@code WHEEL_DELTA}, so one notch is
 *       120 units; GLFW reports 1.0. Some Minecraft versions divide by 120 and others only test the
 *       sign, so matching LWJGL 2 exactly is the only choice that works across the range.
 *   <li><b>Deltas are consumed.</b> {@code getDX()}, {@code getDY()} and {@code getDWheel()} return
 *       what has accumulated since they were last read and reset it, so they must not be recomputed
 *       from the position.
 * </ol>
 *
 * <p>Buttons, motion and the wheel share one queue. That is not tidiness: it is what makes a
 * click's {@code getEventX()} / {@code getEventY()} the coordinates the pointer had when the button
 * went down, rather than wherever it had drifted to by the end of the poll.
 */
public final class MouseState {

    /** LWJGL 2's mouse queue is the same size as its keyboard one. */
    private static final int QUEUE_SIZE = 50;

    /** LWJGL 2 reports a wheel notch as Win32 does; GLFW reports it as 1.0. */
    private static final int WHEEL_UNITS_PER_NOTCH = 120;

    /** LWJGL 2's non-button events carry this instead of a button index. */
    private static final int NO_BUTTON = -1;

    private static final int[] queuedButton = new int[QUEUE_SIZE];
    private static final boolean[] queuedState = new boolean[QUEUE_SIZE];
    private static final int[] queuedX = new int[QUEUE_SIZE];
    private static final int[] queuedY = new int[QUEUE_SIZE];
    private static final int[] queuedDx = new int[QUEUE_SIZE];
    private static final int[] queuedDy = new int[QUEUE_SIZE];
    private static final int[] queuedWheel = new int[QUEUE_SIZE];
    private static final long[] queuedNanos = new long[QUEUE_SIZE];

    private static int head;
    private static int count;

    private static int eventButton = NO_BUTTON;
    private static boolean eventState;
    private static int eventX;
    private static int eventY;
    private static int eventDx;
    private static int eventDy;
    private static int eventWheel;
    private static long eventNanos;

    /**
     * The last position GLFW reported, in GLFW's own coordinates.
     *
     * <p>Kept separately from {@link #x} because while the pointer is grabbed the native shim
     * reports a virtual position that grows without bound — which is what makes the deltas keep
     * coming once the player has swept past the edge of the screen — whereas the position the game
     * reads stays inside the window.
     */
    private static int rawX;
    private static int rawY;

    private static int x;
    private static int y;

    private static int dx;
    private static int dy;
    private static int wheel;

    private static boolean grabbed;

    /**
     * Whether the reported position is held inside the window.
     *
     * <p>On by default, as in LWJGL 2. It has to be clamped after each step rather than derived
     * from the raw position, so that a pointer driven far past the edge starts moving again the
     * instant it comes back rather than after an equal journey in reverse.
     */
    private static boolean clipToWindow = true;

    /** Where the pointer was when the grab started, so releasing can put it back. */
    private static int grabX;
    private static int grabY;

    private MouseState() {
    }

    /** Adopts the pointer's current position without treating the jump to it as movement. */
    public static void reset() {
        head = 0;
        count = 0;
        dx = 0;
        dy = 0;
        wheel = 0;

        long window = Window.handle();
        if (window != NULL) {
            double[] px = new double[1];
            double[] py = new double[1];
            glfwGetCursorPos(window, px, py);
            rawX = (int) px[0];
            rawY = (int) py[0];
        }
        x = clampX(rawX);
        y = clampY(Window.height() - 1 - rawY);

        eventButton = NO_BUTTON;
        eventState = false;
        eventX = x;
        eventY = y;
        eventDx = 0;
        eventDy = 0;
        eventWheel = 0;
        eventNanos = 0L;
    }

    static void onCursorPos(double glfwX, double glfwY) {
        int newRawX = (int) glfwX;
        int newRawY = (int) glfwY;
        int movedX = newRawX - rawX;
        int movedY = -(newRawY - rawY);
        rawX = newRawX;
        rawY = newRawY;

        if (movedX == 0 && movedY == 0) {
            return;
        }

        dx += movedX;
        dy += movedY;
        x = clampX(x + movedX);
        y = clampY(y + movedY);

        push(NO_BUTTON, false, movedX, movedY, 0);
    }

    static void onButton(int button, int action) {
        push(button, action != GLFW_RELEASE, 0, 0, 0);
    }

    static void onScroll(double yoffset) {
        int notches = (int) (yoffset * WHEEL_UNITS_PER_NOTCH);
        if (notches == 0) {
            return;
        }
        wheel += notches;
        push(NO_BUTTON, false, 0, 0, notches);
    }

    public static boolean next() {
        if (count == 0) {
            return false;
        }
        eventButton = queuedButton[head];
        eventState = queuedState[head];
        eventX = queuedX[head];
        eventY = queuedY[head];
        eventDx = queuedDx[head];
        eventDy = queuedDy[head];
        eventWheel = queuedWheel[head];
        eventNanos = queuedNanos[head];
        head = (head + 1) % QUEUE_SIZE;
        count--;
        return true;
    }

    public static int eventButton() {
        return eventButton;
    }

    public static boolean eventState() {
        return eventState;
    }

    public static int eventX() {
        return eventX;
    }

    public static int eventY() {
        return eventY;
    }

    public static int eventDx() {
        return eventDx;
    }

    public static int eventDy() {
        return eventDy;
    }

    public static int eventWheel() {
        return eventWheel;
    }

    public static long eventNanos() {
        return eventNanos;
    }

    public static int x() {
        return x;
    }

    public static int y() {
        return y;
    }

    public static int takeDx() {
        int taken = dx;
        dx = 0;
        return taken;
    }

    public static int takeDy() {
        int taken = dy;
        dy = 0;
        return taken;
    }

    public static int takeWheel() {
        int taken = wheel;
        wheel = 0;
        return taken;
    }

    public static boolean isButtonDown(int button) {
        long window = Window.handle();
        if (window == NULL || button < 0 || button > GLFW_MOUSE_BUTTON_LAST) {
            return false;
        }
        return glfwGetMouseButton(window, button) == GLFW_PRESS;
    }

    public static boolean isInsideWindow() {
        long window = Window.handle();
        return window != NULL && glfwGetWindowAttrib(window, GLFW_HOVERED) != GLFW_FALSE;
    }

    public static boolean isGrabbed() {
        return grabbed;
    }

    public static void setGrabbed(boolean grab) {
        boolean was = grabbed;
        grabbed = grab;

        long window = Window.handle();
        if (window == NULL || was == grab) {
            return;
        }
        if (grab) {
            grabX = x;
            grabY = y;
        }
        glfwSetInputMode(window, GLFW_CURSOR, grab ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        if (!grab) {
            // Put the pointer back where the player left it, as LWJGL 2 does. While grabbed the
            // reported position wanders off with the camera, and releasing without this would drop
            // the cursor somewhere arbitrary in the menu that just opened.
            setPosition(grabX, grabY);
        }
        // Re-anchoring is what stops the camera whipping around the first frame after a menu
        // closes: the position the shim reports either side of a grab change is not continuous,
        // and without this the discontinuity would arrive as one enormous delta.
        reset();
    }

    /** Warps the pointer, in LWJGL 2's bottom-left coordinates. */
    public static void setPosition(int newX, int newY) {
        x = clampX(newX);
        y = clampY(newY);
        eventX = x;
        eventY = y;

        long window = Window.handle();
        if (window == NULL) {
            return;
        }
        rawX = x;
        rawY = Window.height() - 1 - y;
        glfwSetCursorPos(window, rawX, rawY);
    }

    public static boolean isClipToWindow() {
        return clipToWindow;
    }

    public static void setClipToWindow(boolean clip) {
        clipToWindow = clip;
    }

    private static int clampX(int value) {
        return clamp(value, Window.width());
    }

    private static int clampY(int value) {
        return clamp(value, Window.height());
    }

    private static int clamp(int value, int extent) {
        if (!clipToWindow) {
            return value;
        }
        if (value < 0) {
            return 0;
        }
        return value >= extent ? Math.max(extent - 1, 0) : value;
    }

    private static void push(int button, boolean state, int movedX, int movedY, int notches) {
        if (count == QUEUE_SIZE) {
            head = (head + 1) % QUEUE_SIZE;
            count--;
        }
        int tail = (head + count) % QUEUE_SIZE;
        queuedButton[tail] = button;
        queuedState[tail] = state;
        queuedX[tail] = x;
        queuedY[tail] = y;
        queuedDx[tail] = movedX;
        queuedDy[tail] = movedY;
        queuedWheel[tail] = notches;
        queuedNanos[tail] = System.nanoTime();
        count++;
    }
}
