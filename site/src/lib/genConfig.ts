/**
 * Builds the etmc / EasyTier config file that the in-game "Connect via config URL"
 * feature consumes. Runs entirely in the browser (the generator page) — no server.
 *
 * Output format mirrors what dev.l5z12.etmc.core.ImportedConfig expects: a normal
 * EasyTier TOML (network identity, relays, addressing) plus an `[etmc] server`
 * extension naming the Minecraft server's mesh address.
 */
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
}

/** Renders a TOML/YAML double-quoted string with the necessary escaping (the escapes overlap). */
function tstr(s: string): string {
  const escaped = (s ?? '')
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t');
  return `"${escaped}"`;
}

export function validate(i: GenInput): string[] {
  const errs: string[] = [];
  if (!i.network.trim()) errs.push('Network name is required.');
  if (i.relays.length === 0) errs.push('Add at least one relay (EasyTier public node).');
  if (!i.serverIp.trim()) errs.push('Server mesh IP is required.');
  if (!(i.serverPort > 0 && i.serverPort <= 65535)) errs.push('Server port must be 1–65535.');
  if (!i.dhcp && !i.ipv4.trim()) errs.push('Static mode needs a client IPv4 (or switch to DHCP).');
  return errs;
}

export function buildToml(i: GenInput): string {
  const lines: string[] = [];
  lines.push('# etmc / EasyTier server config.');
  lines.push('# Host this over HTTP(S); players connect with "Connect via config URL"');
  lines.push('# or:  /etmc connect <url>');
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

  return lines.join('\n') + '\n';
}

export function validatePaper(i: GenInput): string[] {
  const errs: string[] = [];
  if (!i.network.trim()) errs.push('Network name is required.');
  if (i.relays.length === 0) errs.push('Add at least one relay (EasyTier public node).');
  if (!(i.virtualPort > 0 && i.virtualPort <= 65535)) errs.push('Virtual port must be 1–65535.');
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
  lines.push('# Players connect with the etmc client using the join code (/etmc code) or a config URL.');
  lines.push('');
  lines.push('# EasyTier network identity. Players must use the same name + secret.');
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

/** Splits a comma/newline separated list into trimmed non-empty entries. */
export function splitList(text: string): string[] {
  return (text || '')
    .split(/[\r\n,]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}
