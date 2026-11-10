/**
 * The downloads at /downloads: jars that CI uploads to the etmc-downloads R2 bucket, served by the site's Worker
 * (worker/downloads.ts) and listed by the downloads page. Shared by both, so keep it free of imports.
 *
 * A build has the same path in the URL (after /downloads/) and in the bucket:
 *
 *   release/<tag>             a release, from a v* tag
 *   ci/main/<commit>          a push to main
 *   ci/pr/<number>/<commit>   a commit on a pull request
 *
 * Its jars are at <path>/<file>, and its manifest at index/<path>.json. The uploader (.github/scripts/downloads.sh)
 * writes the manifest last, so a build is listed only once all its jars are up, and removes it first. Keep the
 * patterns below in step with that script.
 */

export type Kind = 'release' | 'main' | 'pr';

/** In the order the page offers them. */
export const LOADERS = ['fabric', 'forge', 'neoforge', 'paper'] as const;
export type Loader = (typeof LOADERS)[number];

export interface FileEntry {
  name: string;
  loader: Loader;
  /** The Minecraft version, such as 1.20.1 or 26.3. */
  mc: string;
  size: number;
  sha256: string;
}

export interface PullRequest {
  number: number;
  title: string;
  author: string;
}

/** What the uploader writes to index/<path>.json. */
export interface Manifest {
  path: string;
  kind: Kind;
  /** The tag, `main`, or the pull request's number. */
  ref: string;
  sha: string;
  /** When it was uploaded, as an ISO date. */
  date: string;
  /** etmc's version, from the jar names. */
  version: string;
  pr?: PullRequest;
  files: FileEntry[];
}

/** A build in the catalog: its manifest without the file list. */
export type Summary = Omit<Manifest, 'files'> & { files: number };

/** Every build, newest first: releases by version, the rest by upload time. */
export interface Catalog {
  releases: Summary[];
  main: Summary[];
  prs: Summary[];
}

const TAG = 'v[0-9][0-9A-Za-z.+_-]{0,62}';
const SHA = '[0-9a-f]{40}';
const PR = '[1-9][0-9]{0,6}';
const BUILD = new RegExp(`^(?:release/(${TAG})|ci/main/(${SHA})|ci/pr/(${PR})/(${SHA}))$`);

/** A build path's parts, or null if it isn't one. */
export function parseBuild(path: string): { kind: Kind; ref: string; sha: string; pr?: number } | null {
  const m = BUILD.exec(path);
  if (!m) return null;
  if (m[1]) return { kind: 'release', ref: m[1], sha: '' };
  if (m[2]) return { kind: 'main', ref: 'main', sha: m[2] };
  return { kind: 'pr', ref: m[3], sha: m[4], pr: Number(m[3]) };
}

/**
 * The prefixes that hold several builds, and stand for the newest of them: `release`, `ci/main` and
 * `ci/pr/<number>`.
 */
export function isScope(path: string): boolean {
  return path === 'release' || path === 'ci/main' || new RegExp(`^ci/pr/${PR}$`).test(path);
}

const JAR = /^etmc-(fabric|forge|neoforge|paper)-(\d+(?:\.\d+)+)-(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.]+)?)\.jar$/;
const CLASSIFIER = /-(?:sources|javadoc|dev|all|slim)\.jar$/;

/** The loader and versions in a jar's name, such as etmc-fabric-1.20.1-0.1.0.jar, or null if it isn't one. */
export function parseJar(name: string): { loader: Loader; mc: string; version: string } | null {
  if (CLASSIFIER.test(name)) return null;
  const m = JAR.exec(name);
  return m ? { loader: m[1] as Loader, mc: m[2], version: m[3] } : null;
}

/**
 * Orders versions such as 1.20.1, 26.3 or 0.2.0-beta.1, the way semver does: a pre-release comes before its release,
 * its numeric parts compare as numbers (beta.2 before beta.10), and build metadata (+…) doesn't count.
 */
export function compareVersions(a: string, b: string): number {
  const [coreA, preA] = splitPre(a);
  const [coreB, preB] = splitPre(b);
  const pa = coreA.split('.').map(Number);
  const pb = coreB.split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const diff = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (diff) return Math.sign(diff);
  }
  if (!preA || !preB) return preA ? -1 : preB ? 1 : 0;
  const ia = preA.split('.');
  const ib = preB.split('.');
  for (let i = 0; i < Math.max(ia.length, ib.length); i++) {
    if (ia[i] === undefined) return -1;
    if (ib[i] === undefined) return 1;
    const na = /^\d+$/.test(ia[i]);
    const nb = /^\d+$/.test(ib[i]);
    if (na && nb) {
      const diff = Number(ia[i]) - Number(ib[i]);
      if (diff) return Math.sign(diff);
    } else if (na !== nb) {
      return na ? -1 : 1;
    } else if (ia[i] !== ib[i]) {
      return ia[i] < ib[i] ? -1 : 1;
    }
  }
  return 0;
}

/** A version's core and pre-release, without its build metadata. */
function splitPre(version: string): [string, string] {
  const core = version.replace(/\+.*$/, '');
  const at = core.indexOf('-');
  return at < 0 ? [core, ''] : [core.slice(0, at), core.slice(at + 1)];
}
