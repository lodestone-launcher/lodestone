package org.lwjgl.openal;

import org.lwjgl.LWJGLException;

/**
 * LWJGL 2's OpenAL bootstrap, declined.
 *
 * <p>Audio is a later phase, and declining it here is the documented way to be without it rather
 * than a gap: paulscode's {@code LibraryLWJGLOpenAL.init} turns this into a
 * {@code SoundSystemException}, {@code SoundSystem} falls through the rest of its library list to
 * the silent one, and Minecraft's own {@code SoundManager} catches what is left and logs that it is
 * turning sound off. The class still has to exist with these signatures, because the library that
 * calls it is on the classpath and is loaded either way.
 */
public final class AL {

    public static void create() throws LWJGLException {
        throw new LWJGLException("Lodestone's LWJGL 2 layer does not implement OpenAL yet");
    }

    public static void create(String deviceArguments, int contextFrequency, int contextRefresh,
                              boolean contextSynchronized) throws LWJGLException {
        create();
    }

    public static void create(String deviceArguments, int contextFrequency, int contextRefresh,
                              boolean contextSynchronized, boolean openDevice)
            throws LWJGLException {
        create();
    }

    public static void destroy() {
    }

    public static boolean isCreated() {
        return false;
    }

    private AL() {
    }
}
