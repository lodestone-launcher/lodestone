package org.lwjgl.opengl;

/**
 * A video mode, as LWJGL 2 describes one.
 *
 * <p>On Android there is only ever one: the size of the surface the Activity gave us. The class is
 * still needed in full, because Minecraft passes these around, compares them and shows them in the
 * video-settings screen.
 */
public final class DisplayMode {

    private final int width;
    private final int height;
    private final int bpp;
    private final int freq;
    private final boolean fullscreen;

    /** A windowed mode, which LWJGL 2 leaves without a bit depth or a refresh rate. */
    public DisplayMode(int width, int height) {
        this(width, height, 0, 0, false);
    }

    /**
     * A fullscreen-capable mode.
     *
     * <p>Package-private in LWJGL 2 as well: only {@code Display} may claim a mode the hardware can
     * switch to.
     */
    DisplayMode(int width, int height, int bpp, int freq) {
        this(width, height, bpp, freq, true);
    }

    private DisplayMode(int width, int height, int bpp, int freq, boolean fullscreen) {
        this.width = width;
        this.height = height;
        this.bpp = bpp;
        this.freq = freq;
        this.fullscreen = fullscreen;
    }

    public boolean isFullscreenCapable() {
        return fullscreen;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getBitsPerPixel() {
        return bpp;
    }

    public int getFrequency() {
        return freq;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DisplayMode)) {
            return false;
        }
        DisplayMode mode = (DisplayMode) other;
        return mode.width == width
                && mode.height == height
                && mode.bpp == bpp
                && mode.freq == freq;
    }

    @Override
    public int hashCode() {
        return width ^ height ^ bpp ^ freq;
    }

    @Override
    public String toString() {
        return width + " x " + height + " x " + bpp + " @" + freq + "Hz";
    }
}
