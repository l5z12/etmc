package dev.l5z12.etmc.ffi;

/**
 * Thrown when an EasyTier FFI call reports failure.
 *
 * <p>Data-plane failures carry the native {@link DataPlaneError} kind, so callers can tell a timeout
 * from a refused connection or a stopped network without parsing the message.
 */
public class EasyTierException extends RuntimeException {

    /** The data-plane error kind, or {@code null} for a lifecycle/config failure. */
    private final DataPlaneError kind;

    public EasyTierException(String message) {
        this(message, (DataPlaneError) null);
    }

    public EasyTierException(String message, Throwable cause) {
        super(message, cause);
        this.kind = null;
    }

    public EasyTierException(String message, DataPlaneError kind) {
        super(message);
        this.kind = kind;
    }

    /** The data-plane error kind, or {@code null} when this isn't a data-plane failure. */
    public DataPlaneError kind() {
        return kind;
    }

    /** Whether this is a data-plane failure of the given kind. */
    public boolean is(DataPlaneError k) {
        return kind == k;
    }
}
