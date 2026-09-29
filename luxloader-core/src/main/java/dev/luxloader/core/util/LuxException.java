package dev.luxloader.core.util;

/** Internal unchecked wrapper for I/O/native failures, preserving their complete cause chain in diagnostics. */
public class LuxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LuxException(String message) {
        super(message);
    }

    public LuxException(String message, Throwable cause) {
        super(message, cause);
    }
}
