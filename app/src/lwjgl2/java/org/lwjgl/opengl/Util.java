package org.lwjgl.opengl;

/** LWJGL 2's GL error helper. */
public final class Util {

    public static void checkGLError() throws OpenGLException {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            throw new OpenGLException(error);
        }
    }

    public static String translateGLErrorString(int errorCode) {
        switch (errorCode) {
            case GL11.GL_NO_ERROR:
                return "No error";
            case GL11.GL_INVALID_ENUM:
                return "Invalid enum";
            case GL11.GL_INVALID_VALUE:
                return "Invalid value";
            case GL11.GL_INVALID_OPERATION:
                return "Invalid operation";
            case GL11.GL_STACK_OVERFLOW:
                return "Stack overflow";
            case GL11.GL_STACK_UNDERFLOW:
                return "Stack underflow";
            case GL11.GL_OUT_OF_MEMORY:
                return "Out of memory";
            case GL30.GL_INVALID_FRAMEBUFFER_OPERATION:
                return "Invalid framebuffer operation";
            default:
                return null;
        }
    }

    private Util() {
    }
}
