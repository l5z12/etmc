import { describe, expect, test } from 'bun:test';
import { handle, type Bucket, type Env, type StoredObject, type StoredObjectBody } from './downloads';
import { compareVersions, parseBuild, parseJar, type Catalog } from '../src/lib/downloads';

const SHA1 = 'a'.repeat(40);
const SHA2 = 'b'.repeat(40);
const SHA3 = 'c'.repeat(40);

/** An in-memory stand-in for the R2 bucket, with as much of R2's behaviour as the Worker relies on. */
function fakeBucket(entries: Record<string, { data?: string; meta?: Record<string, string>; at?: number }>): Bucket {
  const objects = new Map(Object.entries(entries).map(([key, e]) => {
    const data = new TextEncoder().encode(e.data ?? '');
    return [key, { data, meta: e.meta, uploaded: new Date(e.at ?? 0) }];
  }));
  const describe = (key: string): StoredObject | null => {
    const o = objects.get(key);
    if (!o) return null;
    return {
      key,
      size: o.data.length,
      uploaded: o.uploaded,
      httpEtag: `"${key.length}"`,
      customMetadata: o.meta,
      writeHttpMetadata() {},
    };
  };
  return {
    async head(key) {
      return describe(key);
    },
    async get(key, options) {
      const object = describe(key);
      if (!object) return null;
      if (options?.onlyIf?.get('if-none-match') === object.httpEtag) return object;
      let bytes = objects.get(key)!.data;
      const range = /^bytes=(\d+)-(\d+)$/.exec(options?.range?.get('range') ?? '');
      if (range) {
        const [start, end] = [Number(range[1]), Number(range[2])];
        bytes = bytes.slice(start, end + 1);
        object.range = { offset: start, length: end - start + 1 };
      }
      const body: StoredObjectBody = { ...object, body: new Response(bytes).body!, text: async () => new TextDecoder().decode(bytes) };
      return body;
    },
    async list({ prefix = '' }) {
      const keys = [...objects.keys()].filter((k) => k.startsWith(prefix)).sort();
      return { objects: keys.map((k) => describe(k)!), delimitedPrefixes: [], truncated: false };
    },
  };
}

const assets: Env['ASSETS'] = {
  async fetch(request) {
    return new Response(`page ${new URL(request.url).pathname}`, { headers: { 'content-type': 'text/html' } });
  },
};

const manifest = (path: string) => JSON.stringify({ path, files: [] });

const env: Env = {
  ASSETS: assets,
  DOWNLOADS: fakeBucket({
    'release/v0.1.0/etmc-fabric-1.20.1-0.1.0.jar': { data: '0123456789' },
    'index/release/v0.1.0.json': { data: manifest('release/v0.1.0'), meta: { version: '0.1.0', files: '1' }, at: 1 },
    'index/release/v0.10.0.json': { data: manifest('release/v0.10.0'), meta: { version: '0.10.0', files: '90' }, at: 2 },
    'index/release/v0.2.0.json': { data: manifest('release/v0.2.0'), meta: { version: '0.2.0', files: '90' }, at: 3 },
    [`index/ci/main/${SHA1}.json`]: { data: manifest(`ci/main/${SHA1}`), at: 10 },
    [`index/ci/main/${SHA2}.json`]: { data: manifest(`ci/main/${SHA2}`), at: 20 },
    [`index/ci/pr/12/${SHA3}.json`]: {
      data: manifest(`ci/pr/12/${SHA3}`),
      meta: { 'pr-title': encodeURIComponent('修复 <b>bug</b>'), 'pr-author': 'someone' },
      at: 30,
    },
    'release/v0.1.0/index.html': { data: '<script>alert(1)</script>' },
    'decompiles/fabric-loom.tar.zst': { data: 'minecraft' },
  }),
};

const ctx = { waitUntil() {} };
const get = (path: string, init?: RequestInit) => handle(new Request(`https://etmc.l5z12.dev${path}`, init), env, ctx);

describe('routing', () => {
  test('leaves other paths to the static site', async () => {
    expect(await get('/')).toBeNull();
    expect(await get('/docs/hosting')).toBeNull();
    expect(await get('/downloadsx')).toBeNull();
  });

  test('serves the page for /downloads in each language', async () => {
    expect(await (await get('/downloads'))!.text()).toBe('page /downloads');
    expect(await (await get('/zh-tw/downloads'))!.text()).toBe('page /zh-tw/downloads');
    expect((await get('/downloads/'))!.headers.get('location')).toBe('https://etmc.l5z12.dev/downloads');
  });

  test('serves a build page, with its trailing slash', async () => {
    const page = await get(`/zh-cn/downloads/ci/main/${SHA1}/`);
    expect(page!.status).toBe(200);
    expect(await page!.text()).toBe('page /zh-cn/downloads');
    const bare = await get(`/downloads/ci/main/${SHA1}`);
    expect(bare!.headers.get('location')).toBe(`https://etmc.l5z12.dev/downloads/ci/main/${SHA1}/`);
  });

  test('404s a build that is not in the index', async () => {
    const missing = await get(`/downloads/ci/main/${SHA3}/`);
    expect(missing!.status).toBe(404);
    expect(await missing!.text()).toBe('page /404');
    expect((await get('/zh-cn/downloads/nope/'))!.status).toBe(404);
  });

  test('sends a scope to its newest build', async () => {
    const at = (path: string) => get(path).then((r) => r!.headers.get('location'));
    expect(await at('/downloads/release')).toBe('https://etmc.l5z12.dev/downloads/release/v0.10.0/');
    expect(await at('/downloads/ci/main/')).toBe(`https://etmc.l5z12.dev/downloads/ci/main/${SHA2}/`);
    expect(await at('/zh-cn/downloads/ci/pr/12')).toBe(`https://etmc.l5z12.dev/zh-cn/downloads/ci/pr/12/${SHA3}/`);
    expect(await at('/downloads/ci/pr/13')).toBe('https://etmc.l5z12.dev/downloads');
    expect(await at('/downloads/ci')).toBe('https://etmc.l5z12.dev/downloads#dev');
  });

  test('only reads', async () => {
    expect((await get('/downloads/catalog.json', { method: 'POST' }))!.status).toBe(405);
  });
});

describe('files', () => {
  const jar = '/downloads/release/v0.1.0/etmc-fabric-1.20.1-0.1.0.jar';

  test('serves a jar as a download', async () => {
    const response = (await get(jar))!;
    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toBe('application/java-archive');
    expect(response.headers.get('content-disposition')).toBe('attachment; filename="etmc-fabric-1.20.1-0.1.0.jar"');
    expect(response.headers.get('x-content-type-options')).toBe('nosniff');
    expect(await response.text()).toBe('0123456789');
  });

  test('serves ranges and cache checks', async () => {
    const part = (await get(jar, { headers: { range: 'bytes=2-5' } }))!;
    expect(part.status).toBe(206);
    expect(part.headers.get('content-range')).toBe('bytes 2-5/10');
    expect(await part.text()).toBe('2345');

    const etag = (await get(jar))!.headers.get('etag')!;
    expect((await get(jar, { headers: { 'if-none-match': etag } }))!.status).toBe(304);
    expect((await get(jar, { method: 'HEAD' }))!.headers.get('content-length')).toBe('10');
  });

  test('never serves anything but jars inside builds', async () => {
    expect((await get('/downloads/release/v0.1.0/index.html'))!.status).toBe(404);
    expect((await get('/downloads/decompiles/fabric-loom.tar.zst'))!.status).toBe(404);
    expect((await get('/downloads/release/v0.1.0/../../decompiles/fabric-loom.tar.zst'))!.status).toBe(404);
    expect((await get('/downloads/release/v0.9.9/etmc-fabric-1.20.1-0.1.0.jar'))!.status).toBe(404);
    expect((await get('/zh-cn/downloads/release/v0.1.0/etmc-fabric-1.20.1-0.1.0.jar'))!.status).toBe(404);
  });

  test('serves manifests', async () => {
    const response = (await get('/downloads/release/v0.1.0/manifest.json'))!;
    expect(await response.json()).toEqual({ path: 'release/v0.1.0', files: [] });
    expect((await get('/downloads/release/v0.9.9/manifest.json'))!.status).toBe(404);
  });
});

describe('catalog', () => {
  test('lists every build, newest first', async () => {
    const catalog = (await (await get('/downloads/catalog.json'))!.json()) as Catalog;
    expect(catalog.releases.map((b) => b.ref)).toEqual(['v0.10.0', 'v0.2.0', 'v0.1.0']);
    expect(catalog.releases[0]).toMatchObject({ version: '0.10.0', files: 90, date: new Date(2).toISOString() });
    expect(catalog.main.map((b) => b.sha)).toEqual([SHA2, SHA1]);
    expect(catalog.prs).toEqual([{
      path: `ci/pr/12/${SHA3}`, kind: 'pr', ref: '12', sha: SHA3, date: new Date(30).toISOString(), version: '',
      files: 0, pr: { number: 12, title: '修复 <b>bug</b>', author: 'someone' },
    }]);
  });
});

describe('names', () => {
  test('build paths', () => {
    expect(parseBuild('release/v0.1.0-beta.1')).toEqual({ kind: 'release', ref: 'v0.1.0-beta.1', sha: '' });
    expect(parseBuild(`ci/pr/7/${SHA1}`)).toEqual({ kind: 'pr', ref: '7', sha: SHA1, pr: 7 });
    for (const bad of ['release/0.1.0', 'release/v0.1.0/x', `ci/main/${SHA1.slice(1)}`, `ci/pr/0/${SHA1}`, 'ci/main/latest']) {
      expect(parseBuild(bad)).toBeNull();
    }
  });

  test('jar names', () => {
    expect(parseJar('etmc-neoforge-26.1.1-0.1.0.jar')).toEqual({ loader: 'neoforge', mc: '26.1.1', version: '0.1.0' });
    expect(parseJar('etmc-paper-1.21-0.2.0-beta.1.jar')).toEqual({ loader: 'paper', mc: '1.21', version: '0.2.0-beta.1' });
    for (const bad of ['etmc-fabric-1.20.1-0.1.0-sources.jar', 'etmc-common-0.1.0.jar', 'etmc-fabric-1.20.1-0.1.0.html', 'x.jar']) {
      expect(parseJar(bad)).toBeNull();
    }
  });

  test('versions', () => {
    const sorted = ['1.20', '1.9', '26.1', '1.20.1', '1.14.4', '26.1.1'].sort(compareVersions);
    expect(sorted).toEqual(['1.9', '1.14.4', '1.20', '1.20.1', '26.1', '26.1.1']);
    expect(compareVersions('0.2.0-beta.1', '0.2.0')).toBe(-1);
    expect(compareVersions('0.2.0', '0.1.9')).toBe(1);
  });
});
