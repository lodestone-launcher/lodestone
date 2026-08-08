package org.lwjgl.opengl;

/** LWJGL 2's unchecked GL error. */
public class OpenGLException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OpenGLException() {
        super();
    }

    public OpenGLException(int errorCode) {
        this(Util.translateGLErrorString(errorCode));
    }

    public OpenGLException(String message) {
        super(message);
    }

    public OpenGLException(String message, Throwable cause) {
        super(message, cause);
    }

    public OpenGLException(Throwable cause) {
        super(cause);
    }
}
