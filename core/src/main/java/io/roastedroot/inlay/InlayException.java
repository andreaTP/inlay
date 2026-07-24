package io.roastedroot.inlay;

public class InlayException extends RuntimeException {

    public InlayException(String message) {
        super(message);
    }

    public InlayException(String message, Throwable cause) {
        super(message, cause);
    }
}
