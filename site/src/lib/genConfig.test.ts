/**
 * Golden vectors for the wire formats this module re-implements.
 *
 * `genConfig.ts` writes the `etmc://` link and TOML string escaping by hand, in
 * the browser, so a player can build a link without running the mod. The mod writes the same formats
 * in `dev.l5z12.etmc.core.JoinCode` / `Toml.quote`. They are only interchangeable if they agree byte
 * for byte — and nothing but this file and its Java twin says so.
 *
 * The constants below are duplicated, deliberately, in
 * `common/src/test/java/dev/l5z12/etmc/core/WireFormatTest.java`. Either side drifting — a renamed
 * JSON field, a reordered declaration, a different base64 alphabet, a missed escape — fails a test
 * instead of silently shipping links that decode into something subtly different. Update both files
 * together, and only on a deliberate format change (which needs a new `etmc://v2/` version anyway).
 *
 * Run with `bun test` from `site/`.
 */
/// <reference types="bun" />
import { describe, expect, test } from 'bun:test';
import {
  buildJoinLink, buildPaperYaml, buildToml, decodeJoinLink, hostKeyProblem, randomSecret, validateJoinLink, type GenInput,
} from './genConfig';

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

/** PAYLOAD with a host key, which comes last. Twin of WireFormatTest.PAYLOAD_WITH_KEY. */
const HOST_KEY = 'MeAwP9ZBjS-MDni5HyLoyu0Pvkhlbc9HZ-SDT3Abj2I';
const PAYLOAD_WITH_KEY =
  'eyJuZXR3b3JrTmFtZSI6Im15LXNtcCIsIm5ldHdvcmtTZWNyZXQiOiJzM2NyZXQiLCJyZWxheXMiOlsidGNw' +
  'Oi8vcmVsYXkuZXhhbXBsZToxMTAxMCJdLCJob3N0SXAiOiIxMC4xMjYuMTI2LjEiLCJob3N0UG9ydCI6MjU1' +
  'NjUsImxhYmVsIjoiTXkgU2VydmVyIiwiaG9zdEtleSI6Ik1lQXdQOVpCalMtTURuaTVIeUxveXUwUHZraGxi' +
  'YzlIWi1TRFQzQWJqMkkifQ';

/** PAYLOAD_WITH_KEY from a player's world. Twin of WireFormatTest.PAYLOAD_WITH_NAME. */
const PAYLOAD_WITH_NAME =
  'eyJuZXR3b3JrTmFtZSI6Im15LXNtcCIsIm5ldHdvcmtTZWNyZXQiOiJzM2NyZXQiLCJyZWxheXMiOlsidGNw' +
  'Oi8vcmVsYXkuZXhhbXBsZToxMTAxMCJdLCJob3N0SXAiOiIxMC4xMjYuMTI2LjEiLCJob3N0UG9ydCI6MjU1' +
  'NjUsImxhYmVsIjoiTXkgU2VydmVyIiwiaG9zdEtleSI6Ik1lQXdQOVpCalMtTURuaTVIeUxveXUwUHZraGxi' +
  'YzlIWi1TRFQzQWJqMkkiLCJob3N0TmFtZSI6IlN0ZXZlIn0';

describe('join link', () => {
  test('link form is frozen', () => {
    expect(buildJoinLink(vector)).toBe('etmc://v1/' + PAYLOAD);
  });

  test('link form with a host key is frozen', () => {
    expect(buildJoinLink({ ...vector, hostKey: HOST_KEY })).toBe('etmc://v1/' + PAYLOAD_WITH_KEY);
  });

  test('link form from a player\'s world is frozen', () => {
    expect(buildJoinLink({ ...vector, hostKey: HOST_KEY, hostName: 'Steve' })).toBe('etmc://v1/' + PAYLOAD_WITH_NAME);
  });

  test('loading a link and saving it again keeps who it is from', () => {
    const loaded = decodeJoinLink('etmc://v1/' + PAYLOAD_WITH_NAME);
    expect(loaded.hostName).toBe('Steve');
    expect(buildJoinLink({ ...vector, ...loaded })).toBe('etmc://v1/' + PAYLOAD_WITH_NAME);
  });

  test('a blank host key leaves the link exactly as it was', () => {
    expect(buildJoinLink({ ...vector, hostKey: '  ' })).toBe('etmc://v1/' + PAYLOAD);
  });

  test('blank host details fall back to the same defaults as the mod', () => {
    const link = buildJoinLink({ ...vector, serverIp: '', serverPort: 0 });
    const json = JSON.parse(atob(link.slice('etmc://v1/'.length).replace(/-/g, '+').replace(/_/g, '/')));
    expect(json.hostIp).toBe('10.126.126.1');
    expect(json.hostPort).toBe(25565);
  });
});

describe('decoding a join link', () => {
  const b64url = (s: string) =>
    btoa(String.fromCharCode(...new TextEncoder().encode(s)))
      .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

  const fields = {
    network: 'my-smp',
    secret: 's3cret',
    relays: ['tcp://relay.example:11010'],
    serverIp: '10.126.126.1',
    serverPort: 25565,
    label: 'My Server',
  };

  test('the frozen link decodes (twin of WireFormatTest.frozenLinkStillDecodesHere)', () => {
    expect(decodeJoinLink('etmc://v1/' + PAYLOAD)).toEqual(fields);
  });

  test('round-trips non-ASCII text', () => {
    const out = decodeJoinLink(buildJoinLink({ ...vector, label: 'héllo — ✓', secret: 'пароль' }));
    expect(out.label).toBe('héllo — ✓');
    expect(out.secret).toBe('пароль');
  });

  test('accepts what the mod accepts', () => {
    expect(decodeJoinLink(`  etmc://v1/${PAYLOAD}\n`)).toEqual(fields);
    expect(decodeJoinLink(PAYLOAD)).toEqual(fields);
    expect(decodeJoinLink('ETMC://v2/' + PAYLOAD)).toEqual(fields);
  });

  test('falls back to the same host defaults as the mod', () => {
    const out = decodeJoinLink(b64url(JSON.stringify({ networkName: 'n', hostIp: ' ', hostPort: 70000 })));
    expect(out).toEqual({ network: 'n', secret: '', relays: [], serverIp: '10.126.126.1', serverPort: 25565, label: '' });
  });

  test('rejects anything that is not a usable link', () => {
    expect(() => decodeJoinLink('   ')).toThrow('Paste');
    expect(() => decodeJoinLink('ETMC://v1/config/https://example.com/etmc.toml')).toThrow('config URL');
    expect(() => decodeJoinLink('etmc://v2/config/https://example.com/etmc.toml')).toThrow('newer version');
    expect(() => decodeJoinLink('etmc://v1/not+base64!')).toThrow('valid');
    expect(() => decodeJoinLink('etmc://v1/a')).toThrow('valid');
    expect(() => decodeJoinLink('etmc://v1/' + b64url('{oops'))).toThrow('damaged');
    expect(() => decodeJoinLink('etmc://v1/' + b64url('[1]'))).toThrow('damaged');
    expect(() => decodeJoinLink('etmc://v1/' + b64url('{"relays":[]}'))).toThrow('network name');
    expect(() => decodeJoinLink('etmc://v1/')).toThrow('network name');
  });
});

describe('host keys', () => {
  const linkWith = (hostKey: unknown) => 'etmc://v1/' + Buffer.from(JSON.stringify({ networkName: 'n', hostKey })).toString('base64url');

  test('loading a link keeps its key, so saving it again keeps the host check', () => {
    const loaded = decodeJoinLink('etmc://v1/' + PAYLOAD_WITH_KEY);
    expect(loaded.hostKey).toBe(HOST_KEY);
    expect(buildJoinLink({ ...vector, ...loaded })).toBe('etmc://v1/' + PAYLOAD_WITH_KEY);
  });

  test('a link without a key has none', () => {
    expect(decodeJoinLink('etmc://v1/' + PAYLOAD).hostKey).toBeUndefined();
  });

  test('a damaged key is refused, not dropped', () => {
    expect(() => decodeJoinLink(linkWith('not-a-key'))).toThrow('damaged');
    expect(() => decodeJoinLink(linkWith(''))).toThrow('damaged');
    expect(() => decodeJoinLink(linkWith(7))).toThrow('damaged');
  });

  test('the form only takes a real key', () => {
    expect(hostKeyProblem('')).toBeNull();
    expect(hostKeyProblem(HOST_KEY)).toBeNull();
    expect(hostKeyProblem(HOST_KEY.slice(1))).not.toBeNull();
    expect(validateJoinLink({ ...vector, hostKey: 'nope' })).toHaveLength(1);
  });

  test('a player config carries the key', () => {
    expect(buildToml({ ...vector, hostKey: HOST_KEY })).toContain(`host_key = "${HOST_KEY}"`);
    expect(buildToml(vector)).not.toContain('host_key');
  });
});

describe('network secrets', () => {
  test('random secrets match the form the mod generates', () => {
    const secrets = Array.from({ length: 200 }, randomSecret);
    for (const s of secrets) expect(s).toMatch(/^[a-km-np-z2-9]{12}$/);
    expect(new Set(secrets).size).toBe(secrets.length);
  });

  test('generated files warn that the secret is plain text', () => {
    expect(buildToml(vector)).toContain('WARNING: the secret below is plain text');
    expect(buildPaperYaml(vector)).toContain('WARNING: the secret is plain text');
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
