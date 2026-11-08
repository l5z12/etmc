/**
 * Translates the site into every non-English locale with DeepSeek.
 *
 *   DEEPSEEK_API_KEY=... bun run translate            # translate what changed
 *   bun run translate --check                         # list stale translations, exit 1 if any
 *   bun run translate --force hosting                 # retranslate files whose path contains "hosting"
 *
 * English is the source, and only text is translated: the docs in src/content/docs/en/*.md go to
 * src/content/docs/<locale>/, and the rest of the site's text, in src/i18n/en.json, goes to
 * src/i18n/<locale>.json. The pages that show them exist once, for every language.
 *
 * translate.lock.json records the English each translation was made from, so only files whose
 * English changed are sent again. The old translation goes along, so unchanged sentences keep their
 * wording, and hand fixes stay until their English changes. Every reply is checked to keep the
 * source's markup, links, code and placeholders before it's written.
 */
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, rmSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { locales, type Locale } from '../src/i18n/locales';
import type { Strings } from '../src/i18n';

/** Bump to retranslate everything after changing the instructions below. */
const PROMPT_VERSION = 2;
const MODEL = process.env.DEEPSEEK_MODEL ?? 'deepseek-flash';
const API = 'https://api.deepseek.com/chat/completions';
const PARALLEL = 4;
const ATTEMPTS = 3;

const SITE = fileURLToPath(new URL('..', import.meta.url));
const DOCS = 'src/content/docs';
const LOCK = join(SITE, 'scripts/translate.lock.json');
const MOD_LANG = join(SITE, '../mod/src/main/resources/assets/etmc/lang');

type Target = Exclude<Locale, 'en'>;

const LANGUAGES: Record<Target, { name: string; style: string; modLang: string; vanilla: Record<string, string> }> = {
  'zh-cn': {
    name: 'Simplified Chinese, as written in mainland China',
    style: 'Use mainland Chinese terms and “” quotation marks.',
    modLang: 'zh_cn.json',
    vanilla: {
      Singleplayer: '单人游戏', Multiplayer: '多人游戏', 'Add Server': '添加服务器', 'Direct Connect': '直接连接',
      'Join Server': '加入服务器', Controls: '按键控制', 'Key Binds': '按键绑定', 'Open to LAN': '对局域网开放',
      'pause menu': '游戏菜单', 'title screen': '标题屏幕', 'Server Address': '服务器地址',
    },
  },
  'zh-tw': {
    name: 'Traditional Chinese, as written in Taiwan',
    style: 'Use Taiwanese terms, not mainland ones (伺服器 not 服务器, 連線 not 连接, 網路 not 网络, 設定 not 设置, '
      + '檔案 not 文件, 連結 not 链接, 預設 not 默认, 資料夾 not 文件夹), and 「」 quotation marks.',
    modLang: 'zh_tw.json',
    vanilla: {
      Singleplayer: '單人遊戲', Multiplayer: '多人遊戲', 'Add Server': '新增伺服器', 'Direct Connect': '直接連線',
      'Join Server': '加入伺服器', Controls: '按鍵設定', 'Key Binds': '按鍵綁定', 'Open to LAN': '在區網上公開',
      'pause menu': '遊戲選單', 'title screen': '標題畫面', 'Server Address': '伺服器位址',
    },
  },
};

function instructions(locale: Target): string {
  const language = LANGUAGES[locale];
  const readLang = (file: string) => JSON.parse(readFileSync(join(MOD_LANG, file), 'utf8')) as Record<string, string>;
  const english = readLang('en_us.json');
  const translated = readLang(language.modLang);
  const line = (en: string, zh: string) => `${JSON.stringify(en)} → ${JSON.stringify(zh)}`;
  const vanilla = Object.entries(language.vanilla).map(([en, zh]) => line(en, zh));
  const mod = Object.keys(english).filter((k) => translated[k]).map((k) => line(english[k], translated[k]));

  // The site's own menu and page names, once its strings are translated, so docs titles match the menus.
  const strings = join(SITE, `src/i18n/${locale}.json`);
  const site: string[] = [];
  if (existsSync(strings)) {
    const en = JSON.parse(read('src/i18n/en.json')) as Strings;
    const zh = JSON.parse(readFileSync(strings, 'utf8')) as Strings;
    const names = (from: Record<string, unknown>, to: Record<string, unknown>) => {
      for (const [key, value] of Object.entries(from)) {
        if (typeof value === 'string' && typeof to[key] === 'string') site.push(line(value, to[key] as string));
      }
    };
    names(en.nav, zh.nav);
    names(en.docs.pages, zh.docs.pages);
  }

  return `You translate the website of etmc, a Minecraft mod, from English into ${language.name}.

You get one file of the site's text: a Markdown docs page, with a title and description in its frontmatter, or a JSON file of the site's other text, whose values may hold HTML. Reply with the whole translated file and nothing else: no explanation and no code fences around it.

What to translate
- In Markdown: the text, and the values of title and description in the frontmatter.
- In JSON: every value. Keep every key, and keep arrays the same length.
- Comments (lines starting with #) inside a code block.

What to keep exactly as it is
- Markdown structure: headings and their levels, list items, table rows and columns (including \\| escapes inside cells), blank lines between blocks, link targets in (parentheses), and ** and * emphasis markers. Write each paragraph, list item and table row on one line.
- HTML: every tag and attribute. You may move an inline tag such as <strong>, <a>, <code> or <em> within its sentence when Chinese word order needs it.
- Code: everything in \`code spans\`, code blocks (apart from comments), <code>, <kbd> and <pre>. Also, anywhere: commands such as /etmc host [network] [secret], file names, paths, config keys, URLs, IP addresses, ports, link formats such as etmc://v1/config/, HTML entities such as &lt;, and {placeholders}.
- Names of products and projects: etmc, EasyTier, Minecraft, Mojang, Microsoft, Paper, Fabric, Fabric API, NeoForge, Forge, ViaFabricPlus, ZeroTier, Tailscale, Cloudflare, Cloudflare Mesh, Cloudflare One Client, Hamachi, e4mc, Essential, playit.gg, GitHub, Modrinth. Licence names such as GPL-3.0, and a dash standing for "unknown".

Style
- Write natural, friendly and concise Chinese for Minecraft players, as a native writer would. Don't translate word for word, and keep the plain, direct tone of the English. Address the reader as 你.
- ${language.style}
- Use full-width punctuation in Chinese sentences, including list separators such as a colon or comma on their own. Put a space between Chinese characters and Latin letters or digits, as in "在 etmc 菜单中" or "2026 年 11 月 5 日", but no space next to punctuation.
- Names of buttons, screens and options, usually in bold or in <strong>, are labels in the game. Use the label from the glossary exactly, so players can find it on screen. Where the English names a label loosely, such as "Settings" for the button "Settings & relays", use the glossary's full label.
- Use the glossary's words for the mod's ideas too, consistently: relay, host, hosting, secret, host key, invite link, network name, config URL.

Glossary: Minecraft's own labels
${vanilla.join('\n')}

Glossary: etmc's in-game text (English → translation)
${mod.join('\n')}${site.length ? `

Glossary: this site's menus and page names. Use these in page titles too.
${site.join('\n')}` : ''}`;
}

/* ---------- Sources and targets ---------- */

interface Job {
  locale: Target;
  kind: 'docs' | 'strings';
  source: string; // relative to SITE
  target: string; // relative to SITE
}

const read = (path: string) => readFileSync(join(SITE, path), 'utf8').replaceAll('\r\n', '\n');

function jobs(): Job[] {
  const docs = readdirSync(join(SITE, DOCS, 'en')).filter((f) => f.endsWith('.md')).sort();
  const out: Job[] = [];
  for (const locale of locales) {
    if (locale === 'en') continue;
    out.push({ locale, kind: 'strings', source: 'src/i18n/en.json', target: `src/i18n/${locale}.json` });
    for (const file of docs) {
      out.push({ locale, kind: 'docs', source: `${DOCS}/en/${file}`, target: `${DOCS}/${locale}/${file}` });
    }
  }
  return out;
}

const HEADER = '# Translated from {source} by scripts/translate.ts. Edit the English, then run `bun run translate`.';

function withHeader(doc: string, job: Job): string {
  return doc.replace(/^---\n/, `---\n${HEADER.replace('{source}', job.source)}\n`);
}

const withoutHeader = (doc: string) => doc.replace(/^---\n# Translated from .*\n/, '---\n');

/* ---------- Checks ---------- */

const URL_RE = /(?:https?|tcp|etmc):\/\/[^\s"'<>)\]}`，。、；：！？（）「」“”]+/g;

const all = (text: string, re: RegExp, group = 0) => [...text.matchAll(re)].map((m) => m[group]);
/** URLs in the text, without a full stop or comma that ends the sentence after them. */
const urls = (text: string) => all(text, URL_RE).map((u) => u.replace(/[.,;:!?]+$/, ''));

function counts(items: string[]): Map<string, number> {
  const map = new Map<string, number>();
  for (const item of items) map.set(item, (map.get(item) ?? 0) + 1);
  return map;
}

function sameItems(name: string, expected: string[], actual: string[], problems: string[]) {
  const want = counts(expected);
  const got = counts(actual);
  for (const [item, n] of want) {
    const m = got.get(item) ?? 0;
    if (m < n) problems.push(`${name} ${JSON.stringify(item)} appears ${m} times, not ${n}.`);
  }
  for (const [item, m] of got) {
    if (!want.has(item)) problems.push(`${name} ${JSON.stringify(item)} isn't in the source.`);
    else if (m > want.get(item)!) problems.push(`${name} ${JSON.stringify(item)} appears ${m} times, not ${want.get(item)}.`);
  }
}

/** The markup a translation must keep: tags, links, URLs and code. */
function sameMarkup(where: string, source: string, output: string, problems: string[]) {
  const tags = /<\/?[A-Za-z][\w-]*/g;
  const hrefs = /href="([^"]*)"/g;
  sameItems(`${where}tag`, all(source, tags), all(output, tags), problems);
  sameItems(`${where}link`, all(source, hrefs, 1), all(output, hrefs, 1), problems);
  sameItems(`${where}URL`, urls(source), urls(output), problems);
  for (const tag of ['code', 'kbd']) {
    const re = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`, 'g');
    sameItems(`${where}<${tag}>`, all(source, re, 1), all(output, re, 1), problems);
  }
}

interface Markdown { front: string; body: string; code: string[] }

/** A docs page split into its frontmatter, its text and its code blocks. */
function parseMarkdown(doc: string): Markdown {
  const match = /^---\n([\s\S]*?)\n---\n([\s\S]*)$/.exec(doc);
  const front = match?.[1] ?? '';
  const code: string[] = [];
  const body = (match?.[2] ?? doc).replace(/^```[^\n]*\n[\s\S]*?^```$/gm, (block) => {
    // Comments may be translated; everything else in a code block must stay.
    code.push(block.split('\n').filter((l) => !/^\s*#/.test(l)).join('\n'));
    return '```';
  });
  return { front, body, code };
}

function checkDocs(source: string, output: string): string[] {
  const problems: string[] = [];
  const en = parseMarkdown(source);
  const zh = parseMarkdown(output);
  const keys = (front: string) => all(front, /^(\w+):/gm, 1);
  sameItems('Frontmatter key', keys(en.front), keys(zh.front), problems);
  sameItems('Code block', en.code, zh.code, problems);
  const lines = (body: string, re: RegExp) => body.split('\n').map((l) => re.exec(l)?.[0]).filter((l): l is string => !!l);
  sameItems('Heading', lines(en.body, /^#{1,6} /), lines(zh.body, /^#{1,6} /), problems);
  sameItems('List item', lines(en.body, /^\s*(\d+\.|[-*]) /).map((l) => l.trim()), lines(zh.body, /^\s*(\d+\.|[-*]) /).map((l) => l.trim()), problems);
  const rows = (body: string) => body.split('\n').filter((l) => l.startsWith('|')).map((l) => `${l.replace(/\\\|/g, '').split('|').length - 2} cells`);
  sameItems('Table row with', rows(en.body), rows(zh.body), problems);
  sameItems('Code span', all(en.body, /`[^`\n]+`/g), all(zh.body, /`[^`\n]+`/g), problems);
  sameItems('Link target', all(en.body, /\]\(([^)]*)\)/g, 1), all(zh.body, /\]\(([^)]*)\)/g, 1), problems);
  const bold = (body: string) => [`${all(body, /\*\*/g).length} ** markers`];
  sameItems('There are', bold(en.body), bold(zh.body), problems);
  sameMarkup('', en.body, zh.body, problems);
  return problems;
}

function checkStrings(source: string, output: string): string[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(output);
  } catch (e) {
    return [`The reply isn't valid JSON: ${(e as Error).message}`];
  }
  const problems: string[] = [];
  const walk = (en: unknown, zh: unknown, path: string) => {
    if (typeof en === 'string') {
      if (typeof zh !== 'string') return void problems.push(`${path} should be a string.`);
      sameItems(`${path}: placeholder`, all(en, /\{\w+\}/g), all(zh, /\{\w+\}/g), problems);
      sameMarkup(`${path}: `, en, zh, problems);
      return;
    }
    if (typeof zh !== 'object' || zh === null) return void problems.push(`${path} should be an object or array.`);
    const enObj = en as Record<string, unknown>;
    const zhObj = zh as Record<string, unknown>;
    for (const key of Object.keys(enObj)) {
      if (!(key in zhObj)) problems.push(`${path}.${key} is missing.`);
      else walk(enObj[key], zhObj[key], `${path}.${key}`);
    }
    for (const key of Object.keys(zhObj)) if (!(key in enObj)) problems.push(`${path}.${key} isn't in the source.`);
  };
  walk(JSON.parse(source), parsed, '$');
  return problems;
}

/* ---------- Tidying ---------- */

// Chinese characters, and Chinese with its punctuation.
const HAN = '[\\u2E80-\\u2FFF\\u3040-\\u9FFF]';
const CJK = '[\\u2E80-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF]';
// A line ending in Chinese, then one starting with Chinese or a Latin word: not a new list item or block.
const LINE_BREAK = new RegExp(`(${CJK}(?:\\*\\*|\\*|\`|\\))*)\\n[ \\t]*(?=(?:\\*\\*|\\[|\`)*(?:${HAN}|[A-Za-z]))`, 'g');
const TAG_BEFORE_LATIN = new RegExp(`(${HAN})(<(?:strong|em|a)\\b[^>]*>[A-Za-z0-9])`, 'g');
const LATIN_BEFORE_TAG = new RegExp(`([A-Za-z0-9]</(?:strong|em|a)>)(${HAN})`, 'g');

/** Spaces Chinese from a link or label in Latin letters, as the rest of the text is spaced. */
function spaceHtml(text: string): string {
  return text.replace(TAG_BEFORE_LATIN, '$1 $2').replace(LATIN_BEFORE_TAG, '$1 $2');
}

/**
 * Tidies a translated docs page: joins lines broken inside Chinese text, which the browser would
 * show as a space, and spaces Chinese from bold text and tags in Latin letters.
 */
export function tidyDocs(doc: string): string {
  const { front } = parseMarkdown(doc);
  const head = `---\n${front}\n---\n`;
  const parts = doc.slice(head.length).split(/(^```[^\n]*\n[\s\S]*?^```$)/m);
  return head + parts.map((part, i) => {
    if (i % 2) return part; // a code block
    return spaceHtml(part.replace(LINE_BREAK, '$1'))
      .replace(/\*\*([^*\n]+?)\*\*/g, (bold, inner: string, at: number, text: string) => {
        const before = text[at - 1] ?? '';
        const after = text[at + bold.length] ?? '';
        const space = (a: string, b: string) => new RegExp(HAN).test(a) && /[A-Za-z0-9]/.test(b) ? ' ' : '';
        return `${space(before, inner[0])}${bold}${space(after, inner.at(-1)!)}`;
      });
  }).join('');
}

/** Tidies every value of a translated strings file the same way. */
function tidyStrings(value: unknown): unknown {
  if (typeof value === 'string') return spaceHtml(value);
  if (Array.isArray(value)) return value.map(tidyStrings);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, v]) => [key, tidyStrings(v)]));
  }
  return value;
}

/* ---------- DeepSeek ---------- */

type Message = { role: 'system' | 'user' | 'assistant'; content: string };

async function complete(key: string, messages: Message[]): Promise<string> {
  const response = await fetch(API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${key}` },
    body: JSON.stringify({ model: MODEL, messages, max_tokens: 65536 }),
    signal: AbortSignal.timeout(15 * 60_000),
  });
  if (!response.ok) throw new Error(`DeepSeek returned HTTP ${response.status}: ${await response.text()}`);
  const body = (await response.json()) as { choices: { message: { content: string }; finish_reason: string }[] };
  const choice = body.choices[0];
  if (choice.finish_reason !== 'stop') throw new Error(`DeepSeek stopped early (${choice.finish_reason}).`);
  return choice.message.content.replace(/^\s*```(?:json|markdown|md)?\n(?=[\s\S]*\n```\s*$)/, '')
    .replace(/\n```\s*$/, '').trim() + '\n';
}

async function translate(key: string, job: Job, source: string, previous: string | undefined): Promise<string> {
  const what = job.kind === 'docs' ? 'docs page' : 'JSON file of the site\'s text';
  let request = `Translate this ${what}, ${job.source}:\n\n${source}`;
  if (previous) {
    request += `\n\nThis is the current translation, made from an earlier version of the English. Keep its wording `
      + `wherever the English hasn't changed, and translate only what has:\n\n${previous}`;
  }
  const messages: Message[] = [{ role: 'system', content: instructions(job.locale) }, { role: 'user', content: request }];
  for (let attempt = 1; ; attempt++) {
    const output = await complete(key, messages);
    const problems = job.kind === 'docs' ? checkDocs(source, output) : checkStrings(source, output);
    if (problems.length === 0) return output;
    if (attempt === ATTEMPTS) throw new Error(`${job.target} still had problems after ${ATTEMPTS} attempts:\n  ${problems.join('\n  ')}`);
    console.log(`  ${job.target}: retrying (${problems.length} problems)`);
    messages.push({ role: 'assistant', content: output }, {
      role: 'user',
      content: `That reply changed things it had to keep:\n${problems.map((p) => `- ${p}`).join('\n')}\n\n`
        + 'Reply with the whole corrected file.',
    });
  }
}

/* ---------- Main ---------- */

const hash = (text: string) => createHash('sha256').update(`${PROMPT_VERSION}\0${text}`).digest('hex').slice(0, 16);

async function main() {
  const args = process.argv.slice(2);
  const check = args.includes('--check');
  const force = args.includes('--force');
  const filters = args.filter((a) => !a.startsWith('--'));

  const lock: Record<string, string> = existsSync(LOCK) ? JSON.parse(readFileSync(LOCK, 'utf8')) : {};
  const allJobs = jobs();
  const wanted = new Set(allJobs.map((j) => j.target));

  const orphans = Object.keys(lock).filter((target) => !wanted.has(target));
  const stale = allJobs.filter((job) => {
    if (filters.length && !filters.some((f) => job.source.includes(f) || job.target.includes(f))) return false;
    return force || !existsSync(join(SITE, job.target)) || lock[job.target] !== hash(read(job.source));
  });

  if (check) {
    for (const job of stale) console.log(`stale: ${job.target}`);
    for (const target of orphans) console.log(`orphaned: ${target}`);
    if (stale.length || orphans.length) {
      console.log('Run `bun run translate` to bring the translations up to date.');
      process.exit(1);
    }
    console.log('Translations are up to date.');
    return;
  }

  for (const target of orphans) {
    rmSync(join(SITE, target), { force: true });
    delete lock[target];
    console.log(`removed ${target}, as its English source is gone`);
  }

  const key = process.env.DEEPSEEK_API_KEY?.trim();
  if (stale.length && !key) {
    console.error('Set DEEPSEEK_API_KEY to translate.');
    process.exit(1);
  }

  const saveLock = () => {
    const sorted = Object.fromEntries(Object.entries(lock).sort(([a], [b]) => a.localeCompare(b)));
    writeFileSync(LOCK, JSON.stringify(sorted, null, 2) + '\n');
  };

  let failed = 0;
  const run = (queue: Job[]) => Promise.all(Array.from({ length: PARALLEL }, async () => {
    for (let job = queue.shift(); job; job = queue.shift()) {
      const source = read(job.source);
      const path = join(SITE, job.target);
      const previous = existsSync(path) ? withoutHeader(read(job.target)) : undefined;
      const started = Date.now();
      try {
        const output = await translate(key!, job, source, previous);
        mkdirSync(dirname(path), { recursive: true });
        writeFileSync(path, job.kind === 'docs'
          ? withHeader(tidyDocs(output), job)
          : JSON.stringify(tidyStrings(JSON.parse(output)), null, 2) + '\n');
        lock[job.target] = hash(source);
        saveLock();
        console.log(`translated ${job.target} (${Math.round((Date.now() - started) / 1000)}s)`);
      } catch (e) {
        failed++;
        console.error(`failed ${job.target}: ${(e as Error).message}`);
      }
    }
  }));
  // Strings first, so the docs get the translated menu and page names.
  await run(stale.filter((job) => job.kind === 'strings'));
  await run(stale.filter((job) => job.kind === 'docs'));
  saveLock();

  if (stale.length === 0) console.log('Translations are up to date.');
  if (failed) process.exit(1);
}

if (import.meta.main) await main();
