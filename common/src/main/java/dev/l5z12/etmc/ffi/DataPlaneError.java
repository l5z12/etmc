package dev.l5z12.etmc.ffi;

/**
 * The stable error kinds of EasyTier's data-plane ABI ({@code DataPlaneErrorKind} in easytier-core).
 * Immediate calls return them negated; a finished operation reports one as its completion status.
 *
 * <p>Each kind carries a plain-language {@link #describe() description}, because these are what a
 * player sees when joining or hosting fails: "the host refused the connection" says what to do next,
 * "data_plane_tcp_connect failed" does not.
 */
public enum DataPlaneError {
    CANCELLED(1, "the operation was cancelled"),
    DEADLINE_EXCEEDED(2, "timed out"),
    INSTANCE_STOPPED(3, "the EasyTier network stopped"),
    HANDLE_CLOSED(4, "the connection was closed"),
    NO_OVERLAY_ROUTE(5, "that address isn't on the EasyTier network (is the host online?)"),
    PATH_NOT_READY(6, "the EasyTier network isn't ready yet (no route to the host so far)"),
    ADDRESS_FAMILY_UNSUPPORTED(7, "only IPv4 addresses are supported"),
    ADDRESS_IN_USE(8, "that port is already in use on the EasyTier network"),
    CONNECTION_REFUSED(9, "the host refused the connection (it isn't hosting right now)"),
    NETWORK_CHANGED(10, "the EasyTier network changed (the virtual IP was reassigned)"),
    RESOURCE_LIMIT(11, "too many open EasyTier connections"),
    IO(12, "EasyTier I/O error"),
    BUFFER_TOO_SMALL(13, "receive buffer too small");

    private final int code;
    private final String description;

    DataPlaneError(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() {
        return code;
    }

    /** A short, player-facing explanation of this error. */
    public String describe() {
        return description;
    }

    /** Maps an ABI status (positive) or return code (negative) to a kind; unknown codes are {@link #IO}. */
    public static DataPlaneError of(int code) {
        int c = Math.abs(code);
        for (DataPlaneError e : values()) {
            if (e.code == c) return e;
        }
        return IO;
    }
}
