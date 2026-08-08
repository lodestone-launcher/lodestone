package org.lwjgl.opengl;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import com.github.lodestone.compat.lwjgl2.FrameLimiter;
import com.github.lodestone.compat.lwjgl2.Window;
import java.awt.Canvas;
import java.nio.ByteBuffer;
import org.lwjgl.LWJGLException;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * LWJGL 2's window, on top of GLFW.
 *
 * <p>The two disagree about who is in charge of the window's size. LWJGL 2 lets the game pick a
 * display mode and then makes a window that size; on Android the surface is whatever the Activity
 * was given and nothing can change it. So every call that would resize, move, decorate or
 * fullscreen the window is recorded and ignored, and the size the game reads back is the surface's.
 *
 * <p>That is not a fudge that leaves the game confused. Minecraft's resize path is driven by
 * {@link #wasResized()} followed by a re-read of {@link #getWidth()} / {@link #getHeight()}, so
 * asking for 854x480 and being handed 2400x1080 converges on the truth within a frame, and every
 * later surface change — a rotation, a fold, entering split screen — arrives through the same path.
 */
public final class Display {

    /**
     * What Minecraft asked for, kept only so {@code getDisplayMode()} answers with what it was
     * told.
     */
    private static DisplayMode requestedMode = new DisplayMode(0, 0);

    private static String title = "Lodestone";
    private static boolean fullscreen;
    private static boolean resizable;
    private static Canvas parent;

    private static volatile boolean created;

    /**
     * 1 for vsync, 0 for none.
     *
     * <p>Independent of {@link #sync(int)}: the video-settings screen drives the two separately,
     * and folding the frame cap into the swap interval would make either option move the other.
     */
    private static int swapInterval;

    private Display() {
    }

    public static DisplayMode getDisplayMode() {
        return requestedMode;
    }

    public static void setDisplayMode(DisplayMode mode) throws LWJGLException {
        if (mode == null) {
            throw new LWJGLException("mode must be non-null");
        }
        requestedMode = mode;
    }

    /**
     * The surface, described as a video mode.
     *
     * <p>The colour depth and refresh rate come from GLFW rather than being invented, so that the
     * video-settings screen has something coherent to show and the fullscreen mode Minecraft builds
     * out of this compares equal to itself.
     */
    public static DisplayMode getDesktopDisplayMode() {
        long monitor = glfwGetPrimaryMonitor();
        if (monitor != NULL) {
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            if (mode != null) {
                int bpp = mode.redBits() + mode.greenBits() + mode.blueBits();
                return new DisplayMode(mode.width(), mode.height(), bpp, mode.refreshRate());
            }
        }
        return new DisplayMode(Window.width(), Window.height(), 32, 60);
    }

    /** The surface is the only mode there is, so the list has one entry. */
    public static DisplayMode[] getAvailableDisplayModes() throws LWJGLException {
        return new DisplayMode[] { getDesktopDisplayMode() };
    }

    public static void create() throws LWJGLException {
        create(new PixelFormat());
    }

    public static void create(PixelFormat format) throws LWJGLException {
        if (created) {
            throw new LWJGLException("Only one LWJGL context may be instantiated at any one time.");
        }
        if (!glfwInit()) {
            // The shim only reports success once a renderer has been brought up, so this is the
            // failure the player sees when neither gl4es nor Zink could reach a live EGL context.
            throw new LWJGLException("Failed to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_DEPTH_BITS, format.getDepthBits());
        glfwWindowHint(GLFW_STENCIL_BITS, format.getStencilBits());
        glfwWindowHint(GLFW_ALPHA_BITS, format.getAlphaBits());
        glfwWindowHint(GLFW_SAMPLES, format.getSamples());

        long window = glfwCreateWindow(
                Math.max(requestedMode.getWidth(), 1),
                Math.max(requestedMode.getHeight(), 1),
                title,
                NULL,
                NULL);
        if (window == NULL) {
            throw new LWJGLException("Failed to create a GLFW window");
        }

        glfwMakeContextCurrent(window);
        GLContext.createFromCurrent();
        glfwSwapInterval(swapInterval);

        Window.adopt(window);
        created = true;

        // LWJGL 2 brings the input devices up as part of creating the display, and versions before
        // 1.6 never call `Mouse.create()` or `Keyboard.create()` themselves.
        Mouse.create();
        Keyboard.create();
        FrameLimiter.reset();
    }

    public static boolean isCreated() {
        return created;
    }

    public static void destroy() {
        if (!created) {
            return;
        }
        Mouse.destroy();
        Keyboard.destroy();
        GLContext.destroyCurrent();
        Window.close();
        created = false;
        // Deliberately not `glfwTerminate()`: that tears the shim's EGL down, and LWJGL 2 allows a
        // `destroy()` to be followed by another `create()`.
    }

    /** Presents the frame and takes in whatever input arrived while it was being drawn. */
    public static void update() {
        update(true);
    }

    public static void update(boolean processMessages) {
        if (!created) {
            throw new IllegalStateException("Display not created");
        }
        swapBuffers();
        Window.refreshSize();
        if (processMessages) {
            Window.poll();
        }
    }

    public static void processMessages() {
        if (!created) {
            throw new IllegalStateException("Display not created");
        }
        Window.poll();
    }

    public static void swapBuffers() {
        if (!created) {
            throw new IllegalStateException("Display not created");
        }
        glfwSwapBuffers(Window.handle());
    }

    public static void sync(int fps) {
        FrameLimiter.sync(fps);
    }

    public static void makeCurrent() throws LWJGLException {
        if (!created) {
            throw new LWJGLException("Display not created");
        }
        glfwMakeContextCurrent(Window.handle());
    }

    public static void releaseContext() throws LWJGLException {
        if (!created) {
            throw new LWJGLException("Display not created");
        }
        glfwMakeContextCurrent(NULL);
    }

    public static boolean isCurrent() throws LWJGLException {
        return created && glfwGetCurrentContext() == Window.handle();
    }

    /**
     * Gamma, brightness and contrast, which Android composites through SurfaceFlinger.
     *
     * <p>There is no per-application ramp to write, so this is accepted and dropped. LWJGL 2
     * behaves the same way on a display whose gamma cannot be set.
     */
    public static void setDisplayConfiguration(float gamma, float brightness, float contrast)
            throws LWJGLException {
    }

    public static boolean isCloseRequested() {
        return created && glfwWindowShouldClose(Window.handle());
    }

    /**
     * Whether the window has focus.
     *
     * <p>Minecraft pauses the game and releases the pointer when this goes false, which is exactly
     * what should happen when the Activity is backgrounded.
     */
    public static boolean isActive() {
        return created && glfwGetWindowAttrib(Window.handle(), GLFW_FOCUSED) != GLFW_FALSE;
    }

    public static boolean isVisible() {
        return isActive();
    }

    /** Nothing damages the surface behind our back, so there is never anything to repaint. */
    public static boolean isDirty() {
        return false;
    }

    public static boolean wasResized() {
        return Window.wasResized();
    }

    public static int getWidth() {
        return Window.width();
    }

    public static int getHeight() {
        return Window.height();
    }

    /** The surface is always at native resolution, so there is nothing to scale. */
    public static float getPixelScaleFactor() {
        return 1.0f;
    }

    public static int getX() {
        return 0;
    }

    public static int getY() {
        return 0;
    }

    public static String getTitle() {
        return title;
    }

    public static void setTitle(String newTitle) {
        title = newTitle == null ? "" : newTitle;
        if (created) {
            glfwSetWindowTitle(Window.handle(), title);
        }
    }

    public static boolean isFullscreen() {
        return fullscreen;
    }

    /** Recorded and ignored: the surface already covers the screen. */
    public static void setFullscreen(boolean newFullscreen) throws LWJGLException {
        fullscreen = newFullscreen;
    }

    public static void setDisplayModeAndFullscreen(DisplayMode mode) throws LWJGLException {
        setDisplayMode(mode);
        setFullscreen(mode.isFullscreenCapable());
    }

    public static boolean isResizable() {
        return resizable;
    }

    /** Recorded and ignored: only Android decides when the surface changes size. */
    public static void setResizable(boolean newResizable) {
        resizable = newResizable;
    }

    /** Recorded and ignored: the window has no position to set. */
    public static void setLocation(int x, int y) {
    }

    /**
     * Recorded and ignored, returning zero icons used.
     *
     * <p>Android shows the Activity's own launcher icon, and the shim has no window decoration to
     * put one in.
     */
    public static int setIcon(ByteBuffer[] icons) {
        return 0;
    }

    public static Canvas getParent() {
        return parent;
    }

    /**
     * Recorded and ignored.
     *
     * <p>Only Minecraft 1.5.2 and earlier reach this, embedding the display in an AWT frame. There
     * is no frame here and no peer to embed into, but the reference is kept so {@code getParent()}
     * answers.
     */
    public static void setParent(Canvas newParent) throws LWJGLException {
        parent = newParent;
    }

    public static void setVSyncEnabled(boolean enabled) {
        setSwapInterval(enabled ? 1 : 0);
    }

    public static void setSwapInterval(int interval) {
        swapInterval = interval;
        if (created) {
            glfwSwapInterval(interval);
        }
    }

    /**
     * The colour LWJGL 2 clears the window to before the first frame.
     *
     * <p>There is no window to paint before the context exists, and the game overwrites the buffer
     * on its first frame anyway.
     */
    public static void setInitialBackground(float red, float green, float blue) {
    }
}
