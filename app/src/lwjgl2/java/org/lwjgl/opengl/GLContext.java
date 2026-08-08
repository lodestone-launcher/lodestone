package org.lwjgl.opengl;

/**
 * LWJGL 2's handle on what the current GL context can do.
 *
 * <p>LWJGL 2 kept one set of capabilities per context and swapped them on {@code useContext}. There
 * is only ever one context here — the Android surface — so this holds a single set and the
 * context-switching half of the API does nothing.
 */
public final class GLContext {

    private static ContextCapabilities capabilities;

    public static ContextCapabilities getCapabilities() {
        if (capabilities == null) {
            throw new IllegalStateException("No OpenGL context is current");
        }
        return capabilities;
    }

    /**
     * Reads the capabilities off the context {@link Display} has just made current.
     *
     * <p>Not part of LWJGL 2's API: LWJGL 2 did this from inside its own window creation, which the
     * layer has taken over.
     */
    public static void createFromCurrent() {
        capabilities = ContextCapabilities.fromCurrentContext();
    }

    /** Not part of LWJGL 2's API. The counterpart of {@link #createFromCurrent()}. */
    public static void destroyCurrent() {
        capabilities = null;
    }

    public static void useContext(Object context) {
    }

    public static void useContext(Object context, boolean forwardCompatible) {
    }

    public static void loadOpenGLLibrary() {
    }

    public static void unloadOpenGLLibrary() {
    }

    private GLContext() {
    }
}
