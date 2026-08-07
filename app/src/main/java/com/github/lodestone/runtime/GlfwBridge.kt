package com.github.lodestone.runtime

import android.view.Surface

/**
 * The input and windowing side of the shim that stands in for GLFW.
 *
 * LWJGL calls GLFW to create a window, make a GL context current and read input. None of that
 * exists on Android, so `liblodestone_glfw.so` implements the same ABI on top of an Android
 * `Surface` and EGL. This object is how the Activity feeds it the surface and the touch events.
 *
 * Everything here is safe to call before the game starts: events that arrive with no window yet are
 * dropped, and the surface is picked up whenever the VM gets around to creating its window.
 */
object GlfwBridge {

    /** Matches GLFW's `GLFW_RELEASE` / `GLFW_PRESS` / `GLFW_REPEAT`. */
    object Action {
        const val RELEASE = 0
        const val PRESS = 1
        const val REPEAT = 2
    }

    /** Matches GLFW's mouse button numbering. */
    object MouseButton {
        const val LEFT = 0
        const val RIGHT = 1
        const val MIDDLE = 2
    }

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("lodestone_glfw")
            loaded = true
        }
    }

    /**
     * Opens the desktop-GL translation layer named by [path].
     *
     * Call this before the VM starts and never from the render thread. gl4es probes the driver from
     * an ELF constructor and leaves no context current on whichever thread triggered the load, so
     * letting LWJGL be the one to open it unbinds the context Minecraft has just made current — the
     * game then dies building its first framebuffer. Opened here, LWJGL's own `dlopen` finds the
     * library already loaded and the constructor does not run again.
     */
    fun loadTranslationLayer(path: String, eglLibrary: String?): Boolean {
        ensureLoaded()
        return nativeLoadTranslationLayer(path, eglLibrary)
    }

    /**
     * Hands the shim the surface to render into, or null when it goes away.
     *
     * The game's render thread keeps running across a surface change, so the shim swaps the EGL
     * window surface underneath it rather than tearing the context down.
     */
    fun setSurface(surface: Surface?, width: Int, height: Int) {
        ensureLoaded()
        nativeSetSurface(surface, width, height)
    }

    /** Reports the drawable size, which GLFW exposes as both window and framebuffer size. */
    fun setSize(width: Int, height: Int) {
        ensureLoaded()
        nativeSetSize(width, height)
    }

    /**
     * Delivers a key event using GLFW key codes.
     *
     * @param key a `GLFW_KEY_*` value
     * @param scancode the platform scancode, which Minecraft uses for its keybinding display
     * @param action one of [Action]
     * @param mods a bitmask of `GLFW_MOD_*`
     */
    fun sendKey(key: Int, scancode: Int, action: Int, mods: Int = 0) {
        ensureLoaded()
        nativeSendKey(key, scancode, action, mods)
    }

    /** Delivers a typed character, which the game needs for chat and text fields. */
    fun sendChar(codepoint: Int) {
        ensureLoaded()
        nativeSendChar(codepoint)
    }

    /**
     * Moves the cursor.
     *
     * While the game has the pointer grabbed — which it does whenever the player is looking around
     * rather than in a menu — the shim reports a continuously growing virtual position, because
     * Minecraft reads deltas and would otherwise stop turning at the screen edge.
     */
    fun sendCursorPos(x: Float, y: Float) {
        ensureLoaded()
        nativeSendCursorPos(x, y)
    }

    /** Moves the cursor by a relative amount, for look-stick and trackpad style input. */
    fun sendCursorDelta(dx: Float, dy: Float) {
        ensureLoaded()
        nativeSendCursorDelta(dx, dy)
    }

    fun sendMouseButton(button: Int, action: Int, mods: Int = 0) {
        ensureLoaded()
        nativeSendMouseButton(button, action, mods)
    }

    fun sendScroll(dx: Float, dy: Float) {
        ensureLoaded()
        nativeSendScroll(dx, dy)
    }

    /**
     * Whether the game currently has the pointer grabbed.
     *
     * The overlay uses this to decide what the touchscreen means: with the pointer grabbed a drag
     * turns the camera, and without it a tap is a click on whatever menu is open.
     */
    fun isCursorGrabbed(): Boolean {
        ensureLoaded()
        return nativeIsCursorGrabbed()
    }

    /** Asks the game to close, as clicking a desktop window's close button would. */
    fun requestClose() {
        ensureLoaded()
        nativeRequestClose()
    }

    @JvmStatic
    private external fun nativeLoadTranslationLayer(path: String, eglLibrary: String?): Boolean

    @JvmStatic
    private external fun nativeSetSurface(surface: Surface?, width: Int, height: Int)

    @JvmStatic
    private external fun nativeSetSize(width: Int, height: Int)

    @JvmStatic
    private external fun nativeSendKey(key: Int, scancode: Int, action: Int, mods: Int)

    @JvmStatic
    private external fun nativeSendChar(codepoint: Int)

    @JvmStatic
    private external fun nativeSendCursorPos(x: Float, y: Float)

    @JvmStatic
    private external fun nativeSendCursorDelta(dx: Float, dy: Float)

    @JvmStatic
    private external fun nativeSendMouseButton(button: Int, action: Int, mods: Int)

    @JvmStatic
    private external fun nativeSendScroll(dx: Float, dy: Float)

    @JvmStatic
    private external fun nativeIsCursorGrabbed(): Boolean

    @JvmStatic
    private external fun nativeRequestClose()
}

/**
 * The GLFW key codes Lodestone's on-screen controls need.
 *
 * GLFW's printable keys are their ASCII values; the rest start at 256. Minecraft binds against
 * these directly, so the overlay has to speak them rather than Android key codes.
 */
object GlfwKeys {
    const val SPACE = 32
    const val A = 65
    const val D = 68
    const val E = 69
    const val Q = 81
    const val S = 83
    const val T = 84
    const val W = 87

    const val ESCAPE = 256
    const val ENTER = 257
    const val TAB = 258
    const val BACKSPACE = 259
    const val F1 = 290
    const val F3 = 292
    const val F5 = 294
    const val F11 = 300
    const val LEFT_SHIFT = 340
    const val LEFT_CONTROL = 341
    const val LEFT_ALT = 342
}
