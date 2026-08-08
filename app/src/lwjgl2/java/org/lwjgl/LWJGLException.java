package org.lwjgl;

/** LWJGL 2's checked exception. Every failure the compatibility layer reports arrives as one. */
public class LWJGLException extends Exception {

    private static final long serialVersionUID = 1L;

    public LWJGLException() {
        super();
    }

    public LWJGLException(String message) {
        super(message);
    }

    public LWJGLException(String message, Throwable cause) {
        super(message, cause);
    }

    public LWJGLException(Throwable cause) {
        super(cause);
    }
}
