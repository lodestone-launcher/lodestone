package org.lwjgl.input;

import com.github.lodestone.compat.lwjgl2.MouseState;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.LWJGLException;

/**
 * LWJGL 2's mouse, on top of GLFW's pointer callbacks.
 *
 * <p>A thin face over {@link MouseState}, which is where the three conversions that separate the
 * two APIs are done: the Y axis is flipped, the wheel is scaled to LWJGL 2's 120 units per notch,
 * and the deltas are consumed by the getter that reads them.
 */
public class Mouse {

    public static final int EVENT_SIZE = 22;

    /** GLFW numbers the first three buttons the same way, so the indices pass straight through. */
    private static final String[] buttonName = { "BUTTON0", "BUTTON1", "BUTTON2", "BUTTON3",
            "BUTTON4", "BUTTON5", "BUTTON6", "BUTTON7" };

    private static final Map<String, Integer> buttonMap = new HashMap<String, Integer>();

    private static boolean created;

    static {
        for (int i = 0; i < buttonName.length; i++) {
            buttonMap.put(buttonName[i], Integer.valueOf(i));
        }
    }

    private Mouse() {
    }

    public static void create() throws LWJGLException {
        if (created) {
            return;
        }
        MouseState.reset();
        created = true;
    }

    public static boolean isCreated() {
        return created;
    }

    public static void destroy() {
        created = false;
        MouseState.setGrabbed(false);
        MouseState.reset();
    }

    /**
     * Draining GLFW's queue is {@code Display.update()}'s job, so there is nothing left to do here.
     *
     * <p>Kept as a no-op for the versions that call it; see {@link Keyboard#poll()}.
     */
    public static void poll() {
    }

    public static boolean next() {
        return MouseState.next();
    }

    public static int getEventButton() {
        return MouseState.eventButton();
    }

    public static boolean getEventButtonState() {
        return MouseState.eventState();
    }

    public static int getEventX() {
        return MouseState.eventX();
    }

    public static int getEventY() {
        return MouseState.eventY();
    }

    public static int getEventDX() {
        return MouseState.eventDx();
    }

    public static int getEventDY() {
        return MouseState.eventDy();
    }

    public static int getEventDWheel() {
        return MouseState.eventWheel();
    }

    public static long getEventNanoseconds() {
        return MouseState.eventNanos();
    }

    public static int getX() {
        return MouseState.x();
    }

    public static int getY() {
        return MouseState.y();
    }

    /** How far the pointer has moved since this was last asked, which resets the accumulator. */
    public static int getDX() {
        return MouseState.takeDx();
    }

    public static int getDY() {
        return MouseState.takeDy();
    }

    public static int getDWheel() {
        return MouseState.takeWheel();
    }

    public static boolean isButtonDown(int button) {
        return MouseState.isButtonDown(button);
    }

    public static int getButtonCount() {
        return buttonName.length;
    }

    public static String getButtonName(int button) {
        if (button < 0 || button >= buttonName.length) {
            return null;
        }
        return buttonName[button];
    }

    public static int getButtonIndex(String name) {
        Integer index = buttonMap.get(name);
        return index == null ? -1 : index.intValue();
    }

    public static boolean hasWheel() {
        return true;
    }

    public static boolean isInsideWindow() {
        return MouseState.isInsideWindow();
    }

    public static boolean isClipMouseCoordinatesToWindow() {
        return MouseState.isClipToWindow();
    }

    public static void setClipMouseCoordinatesToWindow(boolean clip) {
        MouseState.setClipToWindow(clip);
    }

    /** Positions the pointer in LWJGL 2's coordinates, whose origin is the bottom left. */
    public static void setCursorPosition(int x, int y) {
        MouseState.setPosition(x, y);
    }

    public static boolean isGrabbed() {
        return MouseState.isGrabbed();
    }

    /**
     * Captures the pointer, which is how Minecraft says the player is looking around.
     *
     * <p>The native shim answers this with a virtual position that keeps growing while grabbed, so
     * the deltas carry on past the edge of the screen — which is what LWJGL 2 promised and what
     * makes turning work at all. Lodestone's touch overlay reads the same state to decide whether a
     * drag turns the camera or moves a cursor.
     */
    public static void setGrabbed(boolean grab) {
        MouseState.setGrabbed(grab);
    }

    /** There is no system cursor on Android, so there is never one to swap in. */
    public static Cursor setNativeCursor(Cursor cursor) throws LWJGLException {
        return null;
    }

    public static Cursor getNativeCursor() {
        return null;
    }

    public static void updateCursor() {
    }
}
