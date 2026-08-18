package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden vectors for the two formats that leave this codebase and come back in someone else's hands:
 * the {@code etmc://} link / {@code ETMC1:} code, and the TOML string escaping.
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
 * on a deliberate format change (which needs a new {@code ETMC1}/{@code v1} version anyway).
 */
class WireFormatTest {

    /** The shared payload: base64url (no padding) of the JoinCode JSON, in field-declaration order. */
    private static final String PAYLOAD =
            "eyJuZXR3b3JrTmFtZSI6Im15LXNtcCIsIm5ldHdvcmtTZWNyZXQiOiJzM2NyZXQiLCJyZWxheXMiOlsidGNwOi8v"
                    + "cmVsYXkuZXhhbXBsZToxMTAxMCJdLCJob3N0SXAiOiIxMC4xMjYuMTI2LjEiLCJob3N0UG9ydCI6MjU1"
                    + "NjUsImxhYmVsIjoiTXkgU2VydmVyIn0";

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
    void codeFormIsFrozen() {
        assertEquals("ETMC1:" + PAYLOAD, vector().encode());
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
    void stringEscapingIsFrozen() {
        assertEquals(AWKWARD_QUOTED, Toml.quote(AWKWARD));
    }
}
