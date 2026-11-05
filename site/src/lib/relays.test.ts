/**
 * The generator's relay checks, mirroring the mod's `RelaysTest`: both sides must accept and reject
 * the same addresses, or a link the site happily builds would be one the mod refuses (or vice versa).
 */
import { describe, expect, test } from 'bun:test';
import { relayProblem, splitList, validateJoinLink, type GenInput } from './genConfig';

const base: GenInput = {
  network: 'smp',
  secret: 'pw',
  relays: ['tcp://relay.example.com:11010'],
  dhcp: true,
  ipv4: '',
  serverIp: '10.126.126.1',
  serverPort: 25565,
  label: '',
  virtualPort: 25565,
} as GenInput;

describe('relays', () => {
  test('every separator a player might use works', () => {
    expect(splitList(' tcp://a:1, udp://b:2\nws://c ; tcp://d:4 ')).toEqual(['tcp://a:1', 'udp://b:2', 'ws://c', 'tcp://d:4']);
  });

  test('ordinary relay addresses pass', () => {
    for (const ok of ['tcp://relay.example.com:11010', 'udp://1.2.3.4:11010', 'wss://relay.example.com',
      'quic://[2001:db8::1]:11012']) {
      expect(relayProblem(ok)).toBeNull();
    }
  });

  test('a join link pasted as a relay is caught', () => {
    expect(relayProblem('etmc://v1/eyJuZXR3b3JrTmFtZSI6Im4ifQ')).toContain('join link');
    expect(relayProblem('ETMC1:abc')).toContain('join link');
  });

  test('common mistakes are explained', () => {
    expect(relayProblem('relay.example.com:11010')).toContain('no protocol');
    expect(relayProblem('tcp://relay.example.com')).toContain('no port');
    expect(relayProblem('ftp://relay.example.com:21')).toContain("isn't a relay protocol");
  });

  test('a join link needs a valid relay', () => {
    expect(validateJoinLink(base)).toEqual([]);
    expect(validateJoinLink({ ...base, relays: [] }).join(' ')).toContain('relay');
    expect(validateJoinLink({ ...base, relays: ['etmc://v1/xyz'] }).join(' ')).toContain('join link');
  });
});
