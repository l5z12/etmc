/**
 * Serves /downloads from the etmc-downloads R2 bucket (layout: src/lib/downloads.ts).
 *
 *   /downloads                              the downloads page (a static page; its script reads the catalog)
 *   /downloads/catalog.json                 every build, without file lists
 *   /downloads/<build>/                     the downloads page, showing that build
 *   /downloads/<build>/manifest.json        a build's files
 *   /downloads/<build>/<jar>                a jar
 *   /downloads/release, /ci/main, /ci/pr/N  a redirect to the newest build there
 *
 * Pages also answer under /zh-cn/ and /zh-tw/. Only R2 objects named like etmc's jars are ever served, and always as
 * downloads, never as something a browser would render, so nothing in the bucket can act as a page on this domain.
 *
 * The R2 and Workers types below are the parts this file uses, written out so it typechecks with the site's own
 * config and runs under `bun test` with a fake bucket.
 */
import { compareVersions, isScope, parseBuild, parseJar, type Catalog, type Summary } from '../src/lib/downloads';
import { locales, type Locale } from '../src/i18n/locales';

export interface StoredObject {
  key: string;
  size: number;
  uploaded: Date;
  httpEtag: string;
  customMetadata?: Record<string, string>;
  range?: { offset?: number; length?: number; suffix?: number };
  writeHttpMetadata(headers: Headers): void;
}

export interface StoredObjectBody extends StoredObject {
  body: ReadableStream;
  text(): Promise<string>;
}

export interface Listing {
  objects: StoredObject[];
  delimitedPrefixes: string[];
  truncated: boolean;
  cursor?: string;
}

export interface Bucket {
  head(key: string): Promise<StoredObject | null>;
  get(key: string, options?: { range?: Headers; onlyIf?: Headers }): Promise<StoredObject | StoredObjectBody | null>;
  list(options: { prefix?: string; cursor?: string; include?: ('customMetadata' | 'httpMetadata')[] }): Promise<Listing>;
}

export interface Env {
  DOWNLOADS: Bucket;
  ASSETS: { fetch(request: Request): Promise<Response> };
}

export interface Context {
  waitUntil(promise: Promise<unknown>): void;
}

/** How long the catalog and manifests may be cached, in seconds. */
const LIST_TTL = 60;

const prefixed = locales.filter((l) => l !== 'en') as string[];

/** The response for a /downloads request, or null for any other path. */
export async function handle(request: Request, env: Env, ctx: Context): Promise<Response | null> {
  const url = new URL(request.url);
  const parts = url.pathname.split('/');
  let locale: Locale = 'en';
  let at = 1;
  if (prefixed.includes(parts[1])) {
    locale = parts[1] as Locale;
    at = 2;
  }
  if (parts[at] !== 'downloads') return null;
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return new Response('Method not allowed', { status: 405, headers: { allow: 'GET, HEAD' } });
  }

  const rest = parts.slice(at + 1).join('/');
  const trimmed = rest.replace(/\/$/, '');
  const page = locale === 'en' ? '/downloads' : `/${locale}/downloads`;

  if (trimmed === '') return rest === '' && parts.length === at + 1 ? servePage(env, url, page) : redirect(url, page);

  if (locale === 'en' && trimmed === rest) {
    if (rest === 'catalog.json') return catalog(request, env, ctx);
    const slash = rest.lastIndexOf('/');
    const build = rest.slice(0, slash);
    const name = rest.slice(slash + 1);
    if (parseBuild(build)) {
      if (name === 'manifest.json') return manifest(env, build);
      if (parseJar(name)) return jar(request, env, rest, name);
    }
  }

  if (parseBuild(trimmed)) {
    if (!(await env.DOWNLOADS.head(indexKey(trimmed)))) return notFound(env, url, locale);
    return rest === trimmed ? redirect(url, `${page}/${trimmed}/`) : servePage(env, url, page);
  }
  if (isScope(trimmed)) {
    const newest = await newestIn(env.DOWNLOADS, trimmed);
    return redirect(url, newest ? `${page}/${newest}/` : page);
  }
  if (trimmed === 'ci' || trimmed === 'ci/pr') return redirect(url, `${page}#dev`);
  return notFound(env, url, locale);
}

const indexKey = (build: string) => `index/${build}.json`;

async function servePage(env: Env, url: URL, path: string, status = 200): Promise<Response> {
  const page = await env.ASSETS.fetch(new Request(new URL(path, url)));
  return new Response(page.body, { status: page.ok ? status : page.status, headers: page.headers });
}

function notFound(env: Env, url: URL, locale: Locale): Promise<Response> {
  return servePage(env, url, locale === 'en' ? '/404' : `/${locale}/404`, 404);
}

function redirect(url: URL, location: string): Response {
  return new Response(null, { status: 302, headers: { location: new URL(location, url).toString() } });
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': `public, max-age=${LIST_TTL}`,
      'x-content-type-options': 'nosniff',
    },
  });
}

/** The catalog, from the index's listing and its metadata alone: one list call however many builds there are. */
async function catalog(request: Request, env: Env, ctx: Context): Promise<Response> {
  const cache = typeof caches === 'undefined' ? undefined : await caches.open('downloads');
  const key = new Request(request.url);
  const hit = await cache?.match(key);
  if (hit) return hit;

  const response = json(buildCatalog(await listAll(env.DOWNLOADS, 'index/')));
  if (cache) ctx.waitUntil(cache.put(key, response.clone()));
  return response;
}

/** Every build listed in the index, sorted for the page. */
export function buildCatalog(objects: StoredObject[]): Catalog {
  const result: Catalog = { releases: [], main: [], prs: [] };
  for (const object of objects) {
    const path = object.key.replace(/^index\//, '').replace(/\.json$/, '');
    const build = parseBuild(path);
    if (!build || object.key !== indexKey(path)) continue;
    const meta = object.customMetadata ?? {};
    const summary: Summary = {
      path,
      kind: build.kind,
      ref: build.ref,
      sha: build.sha,
      date: object.uploaded.toISOString(),
      version: meta.version ?? '',
      files: Number(meta.files) || 0,
    };
    if (build.kind === 'release') result.releases.push(summary);
    else if (build.kind === 'main') result.main.push(summary);
    else {
      summary.pr = { number: build.pr!, title: decode(meta['pr-title']), author: decode(meta['pr-author']) };
      result.prs.push(summary);
    }
  }
  result.releases.sort((a, b) => compareVersions(b.ref.slice(1), a.ref.slice(1)));
  const byDate = (a: Summary, b: Summary) => b.date.localeCompare(a.date);
  result.main.sort(byDate);
  result.prs.sort(byDate);
  return result;
}

/** The uploader percent-encodes text it puts in R2 metadata, which only holds ASCII. */
function decode(value: string | undefined): string {
  try {
    return decodeURIComponent(value ?? '');
  } catch {
    return '';
  }
}

async function listAll(bucket: Bucket, prefix: string): Promise<StoredObject[]> {
  const objects: StoredObject[] = [];
  let cursor: string | undefined;
  do {
    const page = await bucket.list({ prefix, cursor, include: ['customMetadata'] });
    objects.push(...page.objects);
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  return objects;
}

/** The path of the newest build under a scope such as `ci/main`, or null if it has none. */
async function newestIn(bucket: Bucket, scope: string): Promise<string | null> {
  const catalog = buildCatalog(await listAll(bucket, `index/${scope}/`));
  const builds = scope === 'release' ? catalog.releases : scope === 'ci/main' ? catalog.main : catalog.prs;
  return builds[0]?.path ?? null;
}

async function manifest(env: Env, build: string): Promise<Response> {
  const object = await env.DOWNLOADS.get(indexKey(build));
  if (!object || !('body' in object)) return json({ error: 'not found' }, 404);
  return json(JSON.parse(await object.text()));
}

async function jar(request: Request, env: Env, key: string, name: string): Promise<Response> {
  const headers = new Headers({
    'content-type': 'application/java-archive',
    'content-disposition': `attachment; filename="${name}"`,
    'cache-control': 'public, max-age=3600',
    'accept-ranges': 'bytes',
    'x-content-type-options': 'nosniff',
    'content-security-policy': "default-src 'none'; sandbox",
  });

  if (request.method === 'HEAD') {
    const object = await env.DOWNLOADS.head(key);
    if (!object) return new Response(null, { status: 404 });
    headers.set('etag', object.httpEtag);
    headers.set('content-length', String(object.size));
    return new Response(null, { headers });
  }

  const object = await env.DOWNLOADS.get(key, { range: request.headers, onlyIf: request.headers });
  if (!object) return new Response('Not found', { status: 404 });
  headers.set('etag', object.httpEtag);
  if (!('body' in object)) {
    // A condition failed: 304 for a cache check, 412 for If-Match and the like.
    const cacheCheck = request.headers.has('if-none-match') || request.headers.has('if-modified-since');
    return new Response(null, { status: cacheCheck ? 304 : 412, headers });
  }
  const range = request.headers.has('range') ? contentRange(object) : null;
  if (range) headers.set('content-range', range);
  return new Response(object.body, { status: range ? 206 : 200, headers });
}

function contentRange(object: StoredObject): string | null {
  const r = object.range;
  if (!r) return null;
  let start: number;
  let length: number;
  if (r.suffix !== undefined) {
    length = Math.min(r.suffix, object.size);
    start = object.size - length;
  } else {
    start = r.offset ?? 0;
    length = r.length ?? object.size - start;
  }
  return `bytes ${start}-${start + length - 1}/${object.size}`;
}
