/**
 * Builds the etmc / EasyTier config file that the in-game "Connect via config URL"
 * feature consumes. Runs entirely in the browser (the generator page) — no server.
 *
 * Output format mirrors what dev.l5z12.etmc.core.ImportedConfig expects: a normal
 * EasyTier TOML (network identity, relays, addressing) plus an `[etmc] server`
 * extension naming the Minecraft server's mesh address.
 */
import { config as englishMessages } from '../i18n/en.json';
import { format } from '../i18n/format';

/** The messages shown for a problem with the form or a link, in the page's language. */
export type ConfigMessages = typeof englishMessages;

export interface GenInput {
  network: string;
  secret: string;
  relays: string[];
  dhcp: boolean;
  ipv4: string;
  serverIp: string;
  serverPort: number;
  label: string;
  /** Mesh-side port for the Paper server config (`virtual-port`). */
  virtualPort: number;
  /** The host's public key, from `/etmc status`. Blank makes a link that can't confirm the host. */
  hostKey?: string;
  /** The hosting player's name. No form field: it only passes through when a link is loaded. */
  hostName?: string;
  /** The link's relay is the host itself. No form field either, for the same reason. */
  direct?: boolean;
}

/**
 * Renders a TOML/YAML double-quoted string with the necessary escaping (the escapes overlap).
 * Mirrors `dev.l5z12.etmc.core.Toml.quote` so a config generated here and one written by the mod
 * are byte-identical — including the `\u00xx` form for the remaining control characters, which
 * TOML forbids raw inside a basic string.
 */
function tstr(s: string): string {
  const escaped = (s ?? '')
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t')
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u001F\u007F]/g, (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`);
  return `"${escaped}"`;
}

const RELAY_SCHEMES = ['tcp', 'udp', 'ws', 'wss', 'wg', 'quic', 'faketcp', 'txt', 'srv', 'http', 'https'];
const RELAY_NEEDS_PORT = ['tcp', 'udp', 'wg', 'quic', 'faketcp'];

/**
 * What is wrong with `uri` as a relay address, or `null` if it looks dialable. Mirrors the mod's
 * `Relays.problem`: the classic mistake is pasting an etmc:// join link where a relay goes, which
 * EasyTier can't dial, so joiners never reach the host.
 */
export function relayProblem(uri: string, m: ConfigMessages = englishMessages): string | null {
  const u = (uri || '').trim();
  if (!u) return m.relayEmpty;
  if (/^etmc:\/\//i.test(u)) return format(m.relayIsLink, { start: u.slice(0, 40) });
  const sep = u.indexOf('://');
  if (sep <= 0) return format(m.relayNoScheme, { uri: u, example: `tcp://${u}${u.includes(':') ? '' : ':11010'}` });
  const scheme = u.slice(0, sep).toLowerCase();
  if (!RELAY_SCHEMES.includes(scheme)) return format(m.relayBadScheme, { scheme });
  let url: URL;
  try {
    url = new URL(u.replace(/^[a-z]+:/i, 'http:'));
  } catch {
    return format(m.relayInvalid, { uri: u });
  }
  if (!url.hostname) return format(m.relayNoHost, { uri: u });
  const explicitPort = /:\d+(\/|$)/.test(u.slice(sep + 3).replace(/^\[[^\]]*\]/, ''));
  if (!explicitPort && RELAY_NEEDS_PORT.includes(scheme)) {
    return format(m.relayNoPort, { uri: u });
  }
  return null;
}

function relayErrors(relays: string[], m: ConfigMessages): string[] {
  const out: string[] = [];
  for (const r of relays) {
    const p = relayProblem(r, m);
    if (p) out.push(p);
  }
  return out;
}

/**
 * What is wrong with a host key, or `null` if it's fine or blank. Mirrors `HostKey.isValid`: a key
 * is 32 bytes of base64url without padding, so 43 characters, and the last one only carries 4 bits.
 */
export function hostKeyProblem(key: string | undefined, m: ConfigMessages = englishMessages): string | null {
  const k = (key ?? '').trim();
  if (!k) return null;
  return /^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/.test(k)
    ? null
    : m.hostKeyBad;
}

function hostKeyErrors(key: string | undefined, m: ConfigMessages): string[] {
  const p = hostKeyProblem(key, m);
  return p ? [p] : [];
}

export function validate(i: GenInput, m: ConfigMessages = englishMessages): string[] {
  const errs: string[] = [];
  if (!i.network.trim()) errs.push(m.networkRequired);
  if (i.relays.length === 0) errs.push(m.relayRequired);
  errs.push(...relayErrors(i.relays, m));
  if (!i.serverIp.trim()) errs.push(m.serverIpRequired);
  if (!(i.serverPort > 0 && i.serverPort <= 65535)) errs.push(m.serverPortRange);
  if (!i.dhcp && !i.ipv4.trim()) errs.push(m.staticIpRequired);
  errs.push(...hostKeyErrors(i.hostKey, m));
  return errs;
}

export function buildToml(i: GenInput): string {
  const lines: string[] = [];
  lines.push('# etmc / EasyTier server config.');
  lines.push('# Host this over HTTP(S); players connect with "Connect via config URL"');
  lines.push('# or:  /etmc connect <url>');
  lines.push('#');
  lines.push('# WARNING: the secret below is plain text, and anyone with this file can read it and join.');
  lines.push('# Keep it random. Never use a password you use anywhere else.');
  lines.push('');

  if (i.dhcp) {
    lines.push('dhcp = true');
  } else {
    lines.push(`ipv4 = ${tstr(i.ipv4.trim())}`);
  }
  lines.push('listeners = []');
  lines.push('');

  lines.push('[network_identity]');
  lines.push(`network_name = ${tstr(i.network.trim())}`);
  lines.push(`network_secret = ${tstr(i.secret)}`);
  lines.push('');

  for (const r of i.relays) {
    const uri = r.trim();
    if (!uri) continue;
    lines.push('[[peer]]');
    lines.push(`uri = ${tstr(uri)}`);
  }
  if (i.relays.some((r) => r.trim())) lines.push('');

  lines.push('[flags]');
  lines.push('no_tun = true');
  lines.push('');

  lines.push('[etmc]');
  lines.push(`server = ${tstr(`${i.serverIp.trim()}:${i.serverPort}`)}`);
  if (i.label.trim()) lines.push(`label = ${tstr(i.label.trim())}`);
  if (i.hostKey?.trim()) lines.push(`host_key = ${tstr(i.hostKey.trim())}`);

  return lines.join('\n') + '\n';
}

export function validatePaper(i: GenInput, m: ConfigMessages = englishMessages): string[] {
  const errs: string[] = [];
  if (!i.network.trim()) errs.push(m.networkRequired);
  if (i.relays.length === 0) errs.push(m.relayRequired);
  errs.push(...relayErrors(i.relays, m));
  if (!(i.virtualPort > 0 && i.virtualPort <= 65535)) errs.push(m.virtualPortRange);
  return errs;
}

/**
 * Builds the Paper plugin's `config.yml` (drop in `plugins/etmc/config.yml`). The server's own
 * listen port is read from `server.properties` by the plugin, so it is not part of this file —
 * only the network identity, relays, and the mesh-side virtual port.
 */
export function buildPaperYaml(i: GenInput): string {
  const lines: string[] = [];
  lines.push('# etmc — expose this server over an EasyTier P2P mesh (no port-forwarding).');
  lines.push('# Drop this in plugins/etmc/config.yml, then restart or run /etmc reload.');
  lines.push('# Players connect with the etmc client using the etmc:// link (/etmc link) or a config URL.');
  lines.push('');
  lines.push('# EasyTier network identity. Players must use the same name + secret.');
  lines.push('# WARNING: the secret is plain text here, and anyone with your etmc:// link can read it.');
  lines.push('# Keep it random. Never use a password you use anywhere else.');
  lines.push(`network: ${tstr(i.network.trim())}`);
  lines.push(`secret: ${tstr(i.secret)}`);
  lines.push('');
  lines.push('# Your EasyTier relay node(s) — REQUIRED. e.g. tcp://my.relay:11010');
  if (i.relays.some((r) => r.trim())) {
    lines.push('relays:');
    for (const r of i.relays) {
      const uri = r.trim();
      if (!uri) continue;
      lines.push(`  - ${tstr(uri)}`);
    }
  } else {
    lines.push('relays: []');
  }
  lines.push('');
  lines.push('# Virtual port the server is reachable at on the mesh.');
  lines.push(`virtual-port: ${i.virtualPort > 0 ? i.virtualPort : 25565}`);
  return lines.join('\n') + '\n';
}

/* ---------------------------------------------------------------------------
 * Join link (etmc://)
 *
 * Mirrors dev.l5z12.etmc.core.JoinCode: a JSON descriptor, base64url-encoded
 * (no padding), behind the `etmc://v1/` URL prefix (pasteable into Add Server /
 * Direct Connect). Field names + order match JoinCode's Gson serialization so
 * the link is byte-identical to the one the mod produces. Everything runs in
 * the browser.
 * ------------------------------------------------------------------------- */

const LINK_PREFIX = 'etmc://v1/';
const HOST_VIRTUAL_IP = '10.126.126.1';
const DEFAULT_VIRTUAL_PORT = 25565;

/** UTF-8 → base64url without padding (matches Java's Base64.getUrlEncoder().withoutPadding()). */
function b64urlUtf8(json: string): string {
  const bytes = new TextEncoder().encode(json);
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function validateJoinLink(i: GenInput, m: ConfigMessages = englishMessages): string[] {
  const errs: string[] = [];
  if (!i.network.trim()) errs.push(m.networkRequired);
  // A link without a relay gives joiners no way to reach the host.
  if (i.relays.length === 0) errs.push(m.hostRelayRequired);
  errs.push(...relayErrors(i.relays, m));
  if (!i.serverIp.trim()) errs.push(m.hostIpRequired);
  if (!(i.serverPort > 0 && i.serverPort <= 65535)) errs.push(m.hostPortRange);
  errs.push(...hostKeyErrors(i.hostKey, m));
  return errs;
}

/** The link's base64url payload. */
function joinPayload(i: GenInput): string {
  // Key order mirrors JoinCode's field declaration order (networkName … direct). The optional ones
  // are left out when blank, as Gson leaves out a null, so a link without them keeps its exact bytes.
  const hostKey = i.hostKey?.trim();
  const hostName = i.hostName?.trim();
  const jc = {
    networkName: i.network.trim(),
    networkSecret: i.secret,
    relays: i.relays.map((r) => r.trim()).filter(Boolean),
    hostIp: i.serverIp.trim() || HOST_VIRTUAL_IP,
    hostPort: i.serverPort > 0 && i.serverPort <= 65535 ? i.serverPort : DEFAULT_VIRTUAL_PORT,
    label: i.label.trim(),
    ...(hostKey ? { hostKey } : {}),
    ...(hostName ? { hostName } : {}),
    ...(i.direct ? { direct: true } : {}),
  };
  return b64urlUtf8(JSON.stringify(jc));
}

/** `etmc://v1/<b64>` — pasteable into Minecraft's Add Server / Direct Connect. */
export function buildJoinLink(i: GenInput): string {
  return LINK_PREFIX + joinPayload(i);
}

export type JoinLinkFields = Pick<GenInput, 'network' | 'secret' | 'relays' | 'serverIp' | 'serverPort' | 'label' | 'hostKey' | 'hostName' | 'direct'>;

/**
 * Reads an `etmc://` link back into form fields. Mirrors `JoinCode.decode`: it tolerates surrounding
 * whitespace, a bare payload and any `etmc://<version>/` prefix, and falls back to the same host
 * defaults. Throws an Error with a message fit to show the user.
 */
export function decodeJoinLink(text: string, m: ConfigMessages = englishMessages): JoinLinkFields {
  let s = (text ?? '').trim();
  if (!s) throw new Error(m.linkEmpty);
  const config = /^etmc:\/\/([^/]+)\/config\//i.exec(s);
  if (config) {
    throw new Error(config[1].toLowerCase() === 'v1' ? m.linkIsConfig : m.linkNewer);
  }
  if (/^etmc:\/\//i.test(s)) s = s.slice(s.lastIndexOf('/') + 1).trim();
  if (!/^[A-Za-z0-9_-]*={0,2}$/.test(s)) throw new Error(m.linkInvalid);

  const b64 = s.replace(/=+$/, '').replace(/-/g, '+').replace(/_/g, '/');
  let json: string;
  try {
    const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4));
    json = new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
  } catch {
    throw new Error(m.linkInvalid);
  }

  let jc: unknown = null;
  if (json.trim()) {
    try {
      jc = JSON.parse(json);
    } catch {
      throw new Error(m.linkDamaged);
    }
  }
  if (jc !== null && (typeof jc !== 'object' || Array.isArray(jc))) {
    throw new Error(m.linkDamaged);
  }

  const f = (jc ?? {}) as Record<string, unknown>;
  const text_ = (v: unknown) => (typeof v === 'string' ? v : '');
  const network = text_(f.networkName);
  if (!network.trim()) throw new Error(m.linkNoNetwork);

  // A damaged key is refused, as the mod refuses it: losing it here would turn off the host check.
  const hostKey = f.hostKey === undefined || f.hostKey === null ? undefined : text_(f.hostKey);
  if (hostKey !== undefined && (!hostKey.trim() || hostKeyProblem(hostKey, m))) {
    throw new Error(m.linkDamaged);
  }

  const relays = Array.isArray(f.relays) ? f.relays.filter((r): r is string => typeof r === 'string') : [];
  const port = f.hostPort;
  return {
    network,
    secret: text_(f.networkSecret),
    relays,
    serverIp: text_(f.hostIp).trim() ? text_(f.hostIp) : HOST_VIRTUAL_IP,
    serverPort: typeof port === 'number' && Number.isInteger(port) && port > 0 && port <= 65535 ? port : DEFAULT_VIRTUAL_PORT,
    label: text_(f.label),
    ...(hostKey ? { hostKey } : {}),
    ...(text_(f.hostName) ? { hostName: text_(f.hostName) } : {}),
    ...(f.direct === true ? { direct: true } : {}),
  };
}

/**
 * A random network secret, in the same form the mod generates: 12 characters from an alphabet with
 * no look-alikes. The alphabet has 32 letters, so masking a random byte stays unbiased.
 */
export function randomSecret(): string {
  const alphabet = 'abcdefghijkmnpqrstuvwxyz23456789';
  return Array.from(crypto.getRandomValues(new Uint8Array(12)), (b) => alphabet[b & 31]).join('');
}

/** Splits a list on commas, semicolons, spaces or newlines (as the mod does) into trimmed entries. */
export function splitList(text: string): string[] {
  return (text || '')
    .split(/[\s,;]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}
