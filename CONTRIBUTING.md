# Contributing

The README and the website are for players. Developer docs go here, and in code comments.

## Layout

- `mod/`: the client mod, built with [Stonecutter](https://stonecutter.kikugie.dev/)
  - `src/`: one tree for every loader and version. `src/forge/` and `src/neoforge/` hold those loaders' metadata
  - `nodes/`: the build script for each loader
  - `versions/`: the dependency versions for each node
- `common/`: the networking core and the EasyTier natives, a separate Gradle build for its tests. The mod and the
  plugin compile its sources into their own jars
- `paper/`: the Paper plugin, a separate Gradle build
- `site/`: the website

## Build

CI builds every version, so build only the ones you're working on. Install the JDK each one targets
(17, 21 or 25), plus JDK 8 for Forge. Gradle can't download them for you, so a missing JDK fails the build.

```bash
./gradlew chiseledBuild -Petmc.nodes='1\.21\.10-.*'   # -> mod/versions/<node>/build/libs/
./gradlew -p paper build                              # -> paper/build/libs/
```

Put your proxy and JDK 8 path in `~/.gradle/gradle.properties`, not the repo's `gradle.properties`.

## Test

```bash
./gradlew -p common test   # core tests
cd site && bun test        # site tests
```

The core tests run on JDK 17. Pass `-Pjava_version=21` to run them on another JDK.

The `etmc://` link and TOML output exist twice: in `JoinCode` and `Toml`, and in `site/src/lib/genConfig.ts`.
Change both together, and keep the vectors in `WireFormatTest` and `genConfig.test.ts` in sync.

## Screens

Every etmc screen extends `EtmcBaseScreen`, which draws the shared frame. A screen declares its widgets, labels and
hover hints once, in `build()`, and that runs again on every resize, so keep anything the player chose in fields and
text in `Ui.Field`. Use `draw()` only for what changes while the screen is open.

A layout has to fit 320x240, the smallest GUI the game gives at Auto scale. `EtmcUiGameTest` opens every screen at that
size and at 1080p, in English and Chinese, and fails if a widget is off screen, widgets overlap, or a label doesn't fit
its button. It can't see drawn text, so check the screenshots CI uploads when you change a layout. To run it locally:

```bash
./gradlew :mod:1.21.10-fabric:runClientGameTest -Petmc.nodes='1.21.10-fabric'
```

## Host identity

The network secret can't identify the host: every joiner holds it, and EasyTier lets any member claim any virtual
IP, with the shorter route winning. Credential peers don't change that, and route advertisements are self-declared.
So a link carries the host's public key, and `HostTunnel` runs `Noise_NK_25519_ChaChaPoly_SHA256` over each mesh
stream. A link with a key never falls back to a plain stream. `NoiseNkTest` holds the published test vectors.

A host can opt in to proving its Minecraft account too. `HostAccount` signs the handshake hash with the player
certificate's key, in the host's first frame. A certificate binds a UUID and expires, so the proof can't go in a link:
the joiner checks it per connection, then asks Mojang for the name. A name in a link is a promise to prove it, so one
that isn't proven stops the join. If Mojang can't be reached, the connection is still refused until the player chooses
**Join anyway**.

Keys stop an impostor being believed, not from taking the address, since the shorter route wins. A Paper server with a
`public-address` is the only way to win that: the link's relay is the host and joiners set `disable_p2p`, so the host
is one hop away and any other claimant is two. `DirectHostNativeTest` holds that against the real library.

No Chat Reports makes the game's key manager getter answer empty while chat signing is off, so `McIdentity` reads the
field through `HiddenField`. Only the host's opt-in connection proof uses that key: etmc signs no chat.

Core code logs through `EtmcLog`, which a client points at the game's log. Players post these logs in public, so keep
secrets, links and private key material out of them, and use `HostKey.shortText` to name a key.

## Natives

Rebuild natives with the `natives.yml` workflow. `easytier-update.yml` also proposes updates weekly.

## Website

Run `bun run dev` in `site/` to work on it, and `bun run deploy` to publish it.

The site is in English, Simplified Chinese (`/zh-cn/`) and Traditional Chinese (`/zh-tw/`). Each page in
`src/pages/[...lang]/` is built once for every language, and only its text is translated. Write English only:

- Docs go in `src/content/docs/en/` as Markdown. Add a new page to the list in `DocsLayout.astro` and its name to
  `docs.pages` in `src/i18n/en.json`.
- All other text goes in `src/i18n/en.json`. `astro check` fails if a translation is missing a key.
- Link to other pages with root paths such as `/docs/hosting`. Each language's page points them at its own pages.

After changing English text, run `DEEPSEEK_API_KEY=... bun run translate` in `site/`. It writes
`src/content/docs/zh-*/` and `src/i18n/zh-*.json`, sending only the files whose English changed, with their current
translation, so unchanged sentences keep their wording. It uses the mod's `zh_cn.json` and `zh_tw.json` as a
glossary, so button names match the game. `bun run translate --check` lists translations that are out of date, and
`bun run translate --force <path>` redoes one. Until a docs page is translated, its other languages show the English.

You can fix a translation by hand. The fix stays until its English changes.

## Release

Push a `v*` tag.
