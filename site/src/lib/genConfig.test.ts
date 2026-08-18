/**
 * Golden vectors for the wire formats this module re-implements.
 *
 * `genConfig.ts` writes the `etmc://` link, the `ETMC1:` code and TOML string escaping by hand, in
 * the browser, so a player can build a link without running the mod. The mod writes the same formats
 * in `dev.l5z12.etmc.core.JoinCode` / `Toml.quote`. They are only interchangeable if they agree byte
 * for byte — and nothing but this file and its Java twin says so.
 *
 * The constants below are duplicated, deliberately, in
 * `common/src/test/java/dev/l5z12/etmc/core/WireFormatTest.java`. Either side drifting — a renamed
 * JSON field, a reordered declaration, a different base64 alphabet, a missed escape — fails a test
 * instead of silently shipping links that decode into something subtly different. Update both files
 * together, and only on a deliberate format change (which needs a new `ETMC1`/`v1` version anyway).
 *
 * Run with `bun test` from `site/`.
 */
/// <reference types="bun" />
import { describe, expect, test } from 'bun:test';
import { buildJoinCode, buildJoinLink, buildToml, type GenInput } from './genConfig';

/** The shared payload: base64url (no padding) of the JoinCode JSON, in field-declaration order. */
const PAYLOAD =
  'eyJuZXR3b3JrTmFtZSI6Im15LXNtcCIsIm5ldHdvcmtTZWNyZXQiOiJzM2NyZXQiLCJyZWxheXMiOlsidGNwOi8v' +
  'cmVsYXkuZXhhbXBsZToxMTAxMCJdLCJob3N0SXAiOiIxMC4xMjYuMTI2LjEiLCJob3N0UG9ydCI6MjU1' +
  'NjUsImxhYmVsIjoiTXkgU2VydmVyIn0';

/** Awkward on purpose: every character class the escaping has to handle, in one string. */
const AWKWARD = 'a"b\\c\nd\te\u0001f';
const AWKWARD_QUOTED = '"a\\"b\\\\c\\nd\\te\\u0001f"';

const vector: GenInput = {
  network: 'my-smp',
  secret: 's3cret',
  relays: ['tcp://relay.example:11010'],
  dhcp: true,
  ipv4: '',
  serverIp: '10.126.126.1',
  serverPort: 25565,
  label: 'My Server',
  virtualPort: 25565,
};

describe('join link + code', () => {
  test('link form is frozen', () => {
    expect(buildJoinLink(vector)).toBe('etmc://v1/' + PAYLOAD);
  });

  test('code form is frozen', () => {
    expect(buildJoinCode(vector)).toBe('ETMC1:' + PAYLOAD);
  });

  test('both forms share one payload', () => {
    expect(buildJoinLink(vector).slice('etmc://v1/'.length))
      .toBe(buildJoinCode(vector).slice('ETMC1:'.length));
  });

  test('blank host details fall back to the same defaults as the mod', () => {
    const link = buildJoinLink({ ...vector, serverIp: '', serverPort: 0 });
    const json = JSON.parse(atob(link.slice('etmc://v1/'.length).replace(/-/g, '+').replace(/_/g, '/')));
    expect(json.hostIp).toBe('10.126.126.1');
    expect(json.hostPort).toBe(25565);
  });
});

describe('TOML string escaping', () => {
  test('escaping is frozen', () => {
    const label = buildToml({ ...vector, label: AWKWARD })
      .split('\n')
      .find((l) => l.startsWith('label = '));
    expect(label).toBe('label = ' + AWKWARD_QUOTED);
  });

  test('non-ASCII is left alone', () => {
    const label = buildToml({ ...vector, label: 'héllo — ✓' })
      .split('\n')
      .find((l) => l.startsWith('label = '));
    expect(label).toBe('label = "héllo — ✓"');
  });
});
