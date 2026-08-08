package org.lwjgl.opengl;

/**
 * The framebuffer a caller would like {@code Display.create()} to give it.
 *
 * <p>Immutable: every {@code with*} returns a copy, which is what lets Minecraft write {@code new
 * PixelFormat().withDepthBits(24)} and pass the result straight to {@code Display.create}.
 *
 * <p>The defaults are LWJGL 2's own — no alpha, 8 depth bits, no stencil — and they matter only in
 * so far as anything reads them back, because the EGL config behind the Android surface is fixed
 * and the window hints these become are advisory.
 */
public final class PixelFormat {

    private final int bpp;
    private final int alpha;
    private final int depth;
    private final int stencil;
    private final int samples;
    private final int colorSamples;
    private final int auxBuffers;
    private final int accumBpp;
    private final int accumAlpha;
    private final boolean stereo;
    private final boolean floatingPoint;
    private final boolean floatingPointPacked;
    private final boolean srgb;

    public PixelFormat() {
        this(0, 8, 0);
    }

    public PixelFormat(int alpha, int depth, int stencil) {
        this(alpha, depth, stencil, 0);
    }

    public PixelFormat(int alpha, int depth, int stencil, int samples) {
        this(0, alpha, depth, stencil, samples);
    }

    public PixelFormat(int bpp, int alpha, int depth, int stencil, int samples) {
        this(bpp, alpha, depth, stencil, samples, 0, 0, 0, false);
    }

    public PixelFormat(int bpp, int alpha, int depth, int stencil, int samples, int auxBuffers,
            int accumBpp, int accumAlpha, boolean stereo) {
        this(bpp, alpha, depth, stencil, samples, auxBuffers, accumBpp, accumAlpha, stereo, false);
    }

    public PixelFormat(int bpp, int alpha, int depth, int stencil, int samples, int auxBuffers,
            int accumBpp, int accumAlpha, boolean stereo, boolean floatingPoint) {
        this.bpp = bpp;
        this.alpha = alpha;
        this.depth = depth;
        this.stencil = stencil;
        this.samples = samples;
        this.colorSamples = 0;
        this.auxBuffers = auxBuffers;
        this.accumBpp = accumBpp;
        this.accumAlpha = accumAlpha;
        this.stereo = stereo;
        this.floatingPoint = floatingPoint;
        this.floatingPointPacked = false;
        this.srgb = false;
    }

    private PixelFormat(int bpp, int alpha, int depth, int stencil, int samples, int colorSamples,
            int auxBuffers, int accumBpp, int accumAlpha, boolean stereo, boolean floatingPoint,
            boolean floatingPointPacked, boolean srgb) {
        this.bpp = bpp;
        this.alpha = alpha;
        this.depth = depth;
        this.stencil = stencil;
        this.samples = samples;
        this.colorSamples = colorSamples;
        this.auxBuffers = auxBuffers;
        this.accumBpp = accumBpp;
        this.accumAlpha = accumAlpha;
        this.stereo = stereo;
        this.floatingPoint = floatingPoint;
        this.floatingPointPacked = floatingPointPacked;
        this.srgb = srgb;
    }

    public int getBitsPerPixel() {
        return bpp;
    }

    public PixelFormat withBitsPerPixel(int bpp) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples, colorSamples, auxBuffers,
                accumBpp, accumAlpha, stereo, floatingPoint, floatingPointPacked, srgb);
    }

    public int getAlphaBits() {
        return alpha;
    }

    public PixelFormat withAlphaBits(int alpha) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples, colorSamples, auxBuffers,
                accumBpp, accumAlpha, stereo, floatingPoint, floatingPointPacked, srgb);
    }

    public int getDepthBits() {
        return depth;
    }

    public PixelFormat withDepthBits(int depth) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples, colorSamples, auxBuffers,
                accumBpp, accumAlpha, stereo, floatingPoint, floatingPointPacked, srgb);
    }

    public int getStencilBits() {
        return stencil;
    }

    public PixelFormat withStencilBits(int stencil) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples, colorSamples, auxBuffers,
                accumBpp, accumAlpha, stereo, floatingPoint, floatingPointPacked, srgb);
    }

    public int getSamples() {
        return samples;
    }

    public PixelFormat withSamples(int samples) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples, colorSamples, auxBuffers,
                accumBpp, accumAlpha, stereo, floatingPoint, floatingPointPacked, srgb);
    }

    public PixelFormat withCoverageSamples(int colorSamples) {
        return withCoverageSamples(colorSamples, samples);
    }

    public PixelFormat withCoverageSamples(int colorSamples, int coverageSamples) {
        return new PixelFormat(bpp, alpha, depth, stencil, coverageSamples, colorSamples,
                auxBuffers, accumBpp, accumAlpha, stereo, floatingPoint, floatingPointPacked, srgb);
    }

    public int getAuxBuffers() {
        return auxBuffers;
    }

    public PixelFormat withAuxBuffers(int auxBuffers) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples, colorSamples, auxBuffers,
                accumBpp, accumAlpha, stereo, floatingPoint, floatingPointPacked, srgb);
    }

    public int getAccumulationBitsPerPixel() {
        return accumBpp;
    }

    public PixelFormat withAccumulationBitsPerPixel(int accumBpp) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples, colorSamples, auxBuffers,
                accumBpp, accumAlpha, stereo, floatingPoint, floatingPointPacked, srgb);
    }

    public int getAccumulationAlpha() {
        return accumAlpha;
    }

    public PixelFormat withAccumulationAlpha(int accumAlpha) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples, colorSamples, auxBuffers,
                accumBpp, accumAlpha, stereo, floatingPoint, floatingPointPacked, srgb);
    }

    public boolean isStereo() {
        return stereo;
    }

    public PixelFormat withStereo(boolean stereo) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples, colorSamples, auxBuffers,
                accumBpp, accumAlpha, stereo, floatingPoint, floatingPointPacked, srgb);
    }

    public boolean isFloatingPoint() {
        return floatingPoint;
    }

    public PixelFormat withFloatingPoint(boolean floatingPoint) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples, colorSamples, auxBuffers,
                accumBpp, accumAlpha, stereo, floatingPoint, false, srgb);
    }

    public PixelFormat withFloatingPointPacked(boolean floatingPointPacked) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples, colorSamples, auxBuffers,
                accumBpp, accumAlpha, stereo, false, floatingPointPacked, srgb);
    }

    public boolean isSRGB() {
        return srgb;
    }

    public PixelFormat withSRGB(boolean srgb) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples, colorSamples, auxBuffers,
                accumBpp, accumAlpha, stereo, floatingPoint, floatingPointPacked, srgb);
    }
}
