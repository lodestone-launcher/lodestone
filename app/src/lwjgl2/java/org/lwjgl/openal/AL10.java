package org.lwjgl.openal;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * LWJGL 2's OpenAL 1.0 bindings, declared but not implemented.
 *
 * <p>Nothing reaches these while {@link AL#create()} declines, so they exist for linkage rather
 * than for use: the classes that call them are verified when they load, whether or not the call is
 * ever executed. The set is exactly what {@code com.paulscode:librarylwjglopenal} references, which
 * is the whole of the OpenAL surface Minecraft 1.6.4 and later reach — the client jars call none of
 * it directly.
 */
public final class AL10 {

    public static final int AL_NO_ERROR = 0;

    public static void alGenBuffers(IntBuffer buffers) {
        throw unavailable();
    }

    public static void alDeleteBuffers(IntBuffer buffers) {
        throw unavailable();
    }

    public static boolean alIsBuffer(int buffer) {
        throw unavailable();
    }

    public static int alGetBufferi(int buffer, int parameter) {
        throw unavailable();
    }

    public static void alBufferData(int buffer, int format, ByteBuffer data, int frequency) {
        throw unavailable();
    }

    public static void alGenSources(IntBuffer sources) {
        throw unavailable();
    }

    public static void alDeleteSources(IntBuffer sources) {
        throw unavailable();
    }

    public static int alGetSourcei(int source, int parameter) {
        throw unavailable();
    }

    public static void alSourcef(int source, int parameter, float value) {
        throw unavailable();
    }

    public static void alSourcei(int source, int parameter, int value) {
        throw unavailable();
    }

    public static void alSource(int source, int parameter, FloatBuffer value) {
        throw unavailable();
    }

    public static void alSourcePlay(int source) {
        throw unavailable();
    }

    public static void alSourcePause(int source) {
        throw unavailable();
    }

    public static void alSourceStop(int source) {
        throw unavailable();
    }

    public static void alSourceStop(IntBuffer sources) {
        throw unavailable();
    }

    public static void alSourceRewind(int source) {
        throw unavailable();
    }

    public static void alSourceQueueBuffers(int source, IntBuffer buffers) {
        throw unavailable();
    }

    public static void alSourceUnqueueBuffers(int source, IntBuffer buffers) {
        throw unavailable();
    }

    public static void alListenerf(int parameter, float value) {
        throw unavailable();
    }

    public static void alListener(int parameter, FloatBuffer value) {
        throw unavailable();
    }

    public static void alDopplerFactor(float value) {
        throw unavailable();
    }

    public static void alDopplerVelocity(float value) {
        throw unavailable();
    }

    public static int alGetError() {
        return AL_NO_ERROR;
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("OpenAL is not created");
    }

    private AL10() {
    }
}
