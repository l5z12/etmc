package dev.l5z12.etmc.ffi;

/** Thrown when an EasyTier FFI call reports failure. */
public class EasyTierException extends RuntimeException {
    public EasyTierException(String message) {
        super(message);
    }

    public EasyTierException(String message, Throwable cause) {
        super(message, cause);
    }
}
