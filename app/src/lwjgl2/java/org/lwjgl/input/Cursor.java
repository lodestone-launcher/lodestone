package org.lwjgl.input;

import java.nio.IntBuffer;
import org.lwjgl.LWJGLException;

/**
 * A hardware cursor image, which Android has no equivalent of.
 *
 * <p>The game builds one of these to give itself a custom pointer and then hands it to
 * {@link Mouse#setNativeCursor}. There is no system cursor to replace, so the image is accepted and
 * dropped; {@link #getCapabilities()} reports none, which is the answer LWJGL 2 gives on a platform
 * that cannot do it and which callers already handle.
 */
public class Cursor {

    public static final int CURSOR_ONE_BIT_TRANSPARENCY = 1;
    public static final int CURSOR_8_BIT_ALPHA = 2;
    public static final int CURSOR_ANIMATION = 4;

    public Cursor(int width, int height, int xHotspot, int yHotspot, int numImages,
            IntBuffer images, IntBuffer delays) throws LWJGLException {
    }

    public static int getMinCursorSize() {
        return 0;
    }

    public static int getMaxCursorSize() {
        return 0;
    }

    public static int getCapabilities() {
        return 0;
    }

    public void destroy() {
    }
}
