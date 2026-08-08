package com.github.lodestone.compat.lwjgl2;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;

/**
 * The one GLFW window, and the input callbacks hanging off it.
 *
 * <p>LWJGL 2 splits this state across three unrelated singletons — {@code Display} owns the window,
 * {@code Mouse} and {@code Keyboard} own the input — but on GLFW they are one object: the callbacks
 * are registered per window, and every mouse coordinate has to be flipped against the window's
 * current height. Keeping the handle and the size here is what lets {@code Mouse} do that without
 * reaching back into {@code Display}.
 *
 * <p><b>Thread affinity.</b> GLFW runs callbacks on whichever thread calls {@code glfwPollEvents},
 * and everything here assumes that is also the thread that created the window and reads the events
 * — which is Minecraft's render thread, doing all three naturally. The Android side never touches
 * this class; it posts into the native shim's own queue, which {@code glfwPollEvents} drains.
 */
public final class Window {

    private static long handle = NULL;

    /**
     * The framebuffer size as of the last {@link #refreshSize()}.
     *
     * <p>Cached rather than queried per call because LWJGL 2's {@code Display.getWidth()} is a
     * field read and Minecraft treats it as one, calling it several times per GUI element.
     */
    private static volatile int width;
    private static volatile int height;

    /**
     * Whether the last {@link #refreshSize()} saw a different size than the one before it.
     *
     * <p>Not consumed by the reader: LWJGL 2 recomputes this once per {@code Display.update()} and
     * leaves it set for the whole frame, because Minecraft tests it and then re-reads the
     * dimensions further down the same frame.
     */
    private static boolean resized;

    /** Reused rather than allocated per frame, which {@link #refreshSize()} would otherwise do. */
    private static final int[] queriedWidth = new int[1];
    private static final int[] queriedHeight = new int[1];

    // Held so they are not collected while GLFW still holds their function pointers, and so
    // they can be freed on destroy.
    private static GLFWKeyCallback keyCallback;
    private static GLFWCharCallback charCallback;
    private static GLFWMouseButtonCallback mouseButtonCallback;
    private static GLFWCursorPosCallback cursorPosCallback;
    private static GLFWScrollCallback scrollCallback;

    private Window() {
    }

    public static long handle() {
        return handle;
    }

    public static boolean isOpen() {
        return handle != NULL;
    }

    public static int width() {
        return width;
    }

    public static int height() {
        return height;
    }

    public static boolean wasResized() {
        return resized;
    }

    /** Takes ownership of a freshly created window and starts delivering its input. */
    public static void adopt(long window) {
        handle = window;

        glfwGetFramebufferSize(handle, queriedWidth, queriedHeight);
        width = queriedWidth[0];
        height = queriedHeight[0];
        resized = false;

        keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long win, int key, int scancode, int action, int mods) {
                KeyboardState.onKey(key, action);
            }
        };
        charCallback = new GLFWCharCallback() {
            @Override
            public void invoke(long win, int codepoint) {
                KeyboardState.onChar(codepoint);
            }
        };
        mouseButtonCallback = new GLFWMouseButtonCallback() {
            @Override
            public void invoke(long win, int button, int action, int mods) {
                MouseState.onButton(button, action);
            }
        };
        cursorPosCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long win, double x, double y) {
                MouseState.onCursorPos(x, y);
            }
        };
        scrollCallback = new GLFWScrollCallback() {
            @Override
            public void invoke(long win, double xoffset, double yoffset) {
                MouseState.onScroll(yoffset);
            }
        };

        glfwSetKeyCallback(handle, keyCallback);
        glfwSetCharCallback(handle, charCallback);
        glfwSetMouseButtonCallback(handle, mouseButtonCallback);
        glfwSetCursorPosCallback(handle, cursorPosCallback);
        glfwSetScrollCallback(handle, scrollCallback);
    }

    /** Drains GLFW's callbacks and settles the events they produced. */
    public static void poll() {
        glfwPollEvents();
        // A key press that produced no character has to wait until the poll is over before it
        // can be emitted, because the character callback that would have completed it fires
        // immediately after the key callback and never later.
        KeyboardState.endPoll();
    }

    /** Re-reads the framebuffer size, raising {@link #wasResized()} if it moved. */
    public static void refreshSize() {
        if (handle == NULL) {
            return;
        }
        glfwGetFramebufferSize(handle, queriedWidth, queriedHeight);
        resized = queriedWidth[0] != width || queriedHeight[0] != height;
        if (resized) {
            width = queriedWidth[0];
            height = queriedHeight[0];
        }
    }

    public static void close() {
        if (handle == NULL) {
            return;
        }
        glfwSetKeyCallback(handle, null);
        glfwSetCharCallback(handle, null);
        glfwSetMouseButtonCallback(handle, null);
        glfwSetCursorPosCallback(handle, null);
        glfwSetScrollCallback(handle, null);

        keyCallback.free();
        charCallback.free();
        mouseButtonCallback.free();
        cursorPosCallback.free();
        scrollCallback.free();
        keyCallback = null;
        charCallback = null;
        mouseButtonCallback = null;
        cursorPosCallback = null;
        scrollCallback = null;

        glfwDestroyWindow(handle);
        handle = NULL;
        resized = false;
    }
}
