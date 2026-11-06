/**
 * A tiny isometric voxel renderer. It runs at build time and emits plain SVG polygons, so the
 * site's scenes need no client-side JavaScript. `+x` runs down-right on screen, `+y` down-left and
 * `+z` up.
 */

export interface Cube {
  x: number;
  y: number;
  z: number;
  color: string;
  /** Edge length in cubes, for details smaller than a full block. */
  size?: number;
  /** Wraps the cube in a `<g>` with these attributes, for animation. */
  attrs?: string;
}

const DX = 24;
const DY = 12;
const DZ = 27;

export const project = (x: number, y: number, z: number): [number, number] => [
  (x - y) * DX,
  (x + y) * DY - z * DZ,
];

const points = (...corners: [number, number, number][]) =>
  corners.map((c) => project(...c).map((n) => Math.round(n * 10) / 10).join(',')).join(' ');

/** Scales a hex colour's brightness. Used to shade the three visible faces of a cube. */
export function shade(hex: string, factor: number): string {
  const n = parseInt(hex.slice(1), 16);
  const channel = (shift: number) => Math.min(255, Math.round(((n >> shift) & 255) * factor));
  return `#${[16, 8, 0].map((s) => channel(s).toString(16).padStart(2, '0')).join('')}`;
}

/** Renders cubes back to front, skipping faces hidden by a neighbouring cube. */
export function renderCubes(cubes: Cube[]): string {
  const key = (x: number, y: number, z: number) => `${x},${y},${z}`;
  const solid = new Set(cubes.filter((c) => !c.size && !c.attrs).map((c) => key(c.x, c.y, c.z)));
  const ordered = [...cubes].sort((a, b) => a.x + a.y + a.z - (b.x + b.y + b.z));

  return ordered.map((c) => {
    const { x, y, z, color } = c;
    const s = c.size ?? 1;
    const faces: string[] = [];
    const face = (fill: string, pts: string) =>
      faces.push(`<polygon fill="${fill}" stroke="${fill}" stroke-width=".6" points="${pts}"/>`);

    if (!solid.has(key(x, y + 1, z))) {
      face(shade(color, 0.74), points([x, y + s, z + s], [x + s, y + s, z + s], [x + s, y + s, z], [x, y + s, z]));
    }
    if (!solid.has(key(x + 1, y, z))) {
      face(shade(color, 0.56), points([x + s, y, z + s], [x + s, y + s, z + s], [x + s, y + s, z], [x + s, y, z]));
    }
    if (!solid.has(key(x, y, z + 1))) {
      face(color, points([x, y, z + s], [x + s, y, z + s], [x + s, y + s, z + s], [x, y + s, z + s]));
    }
    const body = faces.join('');
    return c.attrs ? `<g ${c.attrs}>${body}</g>` : body;
  }).join('');
}

/** A small seeded random generator, so every build draws the same scene. */
export function seeded(seed: number): () => number {
  let a = seed;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** A floating island: a round top that tapers to a ragged point below. */
export function island(cx: number, cy: number, radius: number, top: string, soil: string, rock: string, seed: number): Cube[] {
  const random = seeded(seed);
  const cubes: Cube[] = [];
  const depth = Math.round(radius * 1.25);
  const reach = Math.ceil(radius);

  for (let layer = 0; layer <= depth; layer++) {
    const r = radius * (1 - layer / (depth + 1)) ** 0.8;
    for (let x = -reach; x <= reach; x++) {
      for (let y = -reach; y <= reach; y++) {
        const edge = layer === 0 ? 0 : (random() - 0.5) * 1.3;
        if (Math.hypot(x, y) > r + edge) continue;
        const tint = 0.95 + random() * 0.1;
        const base = layer === 0 ? top : layer === 1 ? soil : rock;
        cubes.push({ x: cx + x, y: cy + y, z: -layer, color: shade(base, tint) });
      }
    }
  }
  return cubes;
}

/** A blocky tree standing on the island surface. */
export function tree(x: number, y: number, trunk: string, leaves: string, seed: number): Cube[] {
  const random = seeded(seed);
  const cubes: Cube[] = [];
  for (let z = 1; z <= 3; z++) cubes.push({ x, y, z, color: trunk });
  for (let dz = 3; dz <= 5; dz++) {
    const r = dz === 5 ? 0 : 1;
    for (let dx = -r; dx <= r; dx++) {
      for (let dy = -r; dy <= r; dy++) {
        if (dz === 3 && dx === 0 && dy === 0) continue;
        if (Math.abs(dx) + Math.abs(dy) === 2 && random() < 0.5) continue;
        cubes.push({ x: x + dx, y: y + dy, z: dz, color: shade(leaves, 0.94 + random() * 0.12) });
      }
    }
  }
  return cubes;
}
