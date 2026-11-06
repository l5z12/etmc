package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden vectors for the two formats that leave this codebase and come back in someone else's hands:
 * the {@code etmc://} link, and the TOML string escaping.
 *
 * <p>{@link JoinCodeTest} proves those round-trip through <em>this</em> implementation, which a
 * change of field order or encoder would keep doing while producing entirely different bytes. That
 * matters because there is a <b>second implementation</b>: the site's in-browser generator
 * ({@code site/src/lib/genConfig.ts}) writes the same formats by hand so a player can build a link
 * without the mod. The two are only interchangeable if they agree byte for byte.
 *
 * <p>So the constants below are duplicated, deliberately, in {@code site/src/lib/genConfig.test.ts}
 * ({@code bun test} in {@code site/}). Either side drifting — a renamed JSON field, a reordered
 * declaration, a different base64 alphabet, a missed escape — fails a test instead of silently
 * shipping links that decode into something subtly different. Update both files together, and only
 * on a deliberate format change. That needs a new {@code etmc://v2/} version, unless it only adds an
 * optional field an older etmc can ignore, as {@code hostKey} did.
 */
class WireFormatTest {

    /** The shared payload: base64url (no padding) of the JoinCode JSON, in field-declaration order. */
    private static final String PAYLOAD =
            "eyJuZXR3b3JrTmFtZSI6Im15LXNtcCIsIm5ldHdvcmtTZWNyZXQiOiJzM2NyZXQiLCJyZWxheXMiOlsidGNwOi8v"
                    + "cmVsYXkuZXhhbXBsZToxMTAxMCJdLCJob3N0SXAiOiIxMC4xMjYuMTI2LjEiLCJob3N0UG9ydCI6MjU1"
                    + "NjUsImxhYmVsIjoiTXkgU2VydmVyIn0";

    /** {@link #PAYLOAD} with a host key, which comes last. */
    private static final String HOST_KEY = "MeAwP9ZBjS-MDni5HyLoyu0Pvkhlbc9HZ-SDT3Abj2I";
    private static final String PAYLOAD_WITH_KEY =
            "eyJuZXR3b3JrTmFtZSI6Im15LXNtcCIsIm5ldHdvcmtTZWNyZXQiOiJzM2NyZXQiLCJyZWxheXMiOlsidGNw"
                    + "Oi8vcmVsYXkuZXhhbXBsZToxMTAxMCJdLCJob3N0SXAiOiIxMC4xMjYuMTI2LjEiLCJob3N0UG9ydCI6MjU1"
                    + "NjUsImxhYmVsIjoiTXkgU2VydmVyIiwiaG9zdEtleSI6Ik1lQXdQOVpCalMtTURuaTVIeUxveXUwUHZraGxi"
                    + "YzlIWi1TRFQzQWJqMkkifQ";

    /** {@link #PAYLOAD_WITH_KEY} from a player's world, whose name comes last. */
    private static final String PAYLOAD_WITH_NAME =
            "eyJuZXR3b3JrTmFtZSI6Im15LXNtcCIsIm5ldHdvcmtTZWNyZXQiOiJzM2NyZXQiLCJyZWxheXMiOlsidGNw"
                    + "Oi8vcmVsYXkuZXhhbXBsZToxMTAxMCJdLCJob3N0SXAiOiIxMC4xMjYuMTI2LjEiLCJob3N0UG9ydCI6MjU1"
                    + "NjUsImxhYmVsIjoiTXkgU2VydmVyIiwiaG9zdEtleSI6Ik1lQXdQOVpCalMtTURuaTVIeUxveXUwUHZraGxi"
                    + "YzlIWi1TRFQzQWJqMkkiLCJob3N0TmFtZSI6IlN0ZXZlIn0";

    /** {@link #PAYLOAD_WITH_KEY} from a server that is its own relay. */
    private static final String PAYLOAD_DIRECT =
            "eyJuZXR3b3JrTmFtZSI6Im15LXNtcCIsIm5ldHdvcmtTZWNyZXQiOiJzM2NyZXQiLCJyZWxheXMiOlsidGNw"
                    + "Oi8vcmVsYXkuZXhhbXBsZToxMTAxMCJdLCJob3N0SXAiOiIxMC4xMjYuMTI2LjEiLCJob3N0UG9ydCI6MjU1"
                    + "NjUsImxhYmVsIjoiTXkgU2VydmVyIiwiaG9zdEtleSI6Ik1lQXdQOVpCalMtTURuaTVIeUxveXUwUHZraGxi"
                    + "YzlIWi1TRFQzQWJqMkkiLCJkaXJlY3QiOnRydWV9";

    /** Awkward on purpose: every character class {@code Toml.quote} has to escape, in one string. */
    private static final String AWKWARD = "a\"b\\c\nd\te\u0001f";
    private static final String AWKWARD_QUOTED = "\"a\\\"b\\\\c\\nd\\te\\u0001f\"";

    private static JoinCode vector() {
        return new JoinCode("my-smp", "s3cret", List.of("tcp://relay.example:11010"),
                "10.126.126.1", 25565, "My Server");
    }

    @Test
    void linkFormIsFrozen() {
        assertEquals("etmc://v1/" + PAYLOAD, vector().encodeLink());
    }

    @Test
    void frozenLinkStillDecodesHere() {
        JoinCode out = JoinCode.decode("etmc://v1/" + PAYLOAD);

        assertEquals("my-smp", out.networkName);
        assertEquals("s3cret", out.networkSecret);
        assertEquals(List.of("tcp://relay.example:11010"), out.relays);
        assertEquals("10.126.126.1", out.hostIp);
        assertEquals(25565, out.hostPort);
        assertEquals("My Server", out.label);
    }

    @Test
    void linkFormWithAHostKeyIsFrozen() {
        JoinCode code = vector();
        code.hostKey = HOST_KEY;

        assertEquals("etmc://v1/" + PAYLOAD_WITH_KEY, code.encodeLink());
        assertEquals(HOST_KEY, JoinCode.decode("etmc://v1/" + PAYLOAD_WITH_KEY).hostKey);
    }

    @Test
    void linkFormFromAPlayersWorldIsFrozen() {
        JoinCode code = vector();
        code.hostKey = HOST_KEY;
        code.hostName = "Steve";

        assertEquals("etmc://v1/" + PAYLOAD_WITH_NAME, code.encodeLink());
        assertEquals("Steve", JoinCode.decode("etmc://v1/" + PAYLOAD_WITH_NAME).hostName);
    }

    @Test
    void linkFormFromADirectServerIsFrozen() {
        JoinCode code = vector();
        code.hostKey = HOST_KEY;
        code.direct = Boolean.TRUE;

        assertEquals("etmc://v1/" + PAYLOAD_DIRECT, code.encodeLink());
        assertEquals(Boolean.TRUE, JoinCode.decode("etmc://v1/" + PAYLOAD_DIRECT).direct);
    }

    @Test
    void stringEscapingIsFrozen() {
        assertEquals(AWKWARD_QUOTED, Toml.quote(AWKWARD));
    }
}
