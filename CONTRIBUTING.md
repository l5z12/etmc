# Contributing

The README and the website are for players. Developer docs go here, and in code comments.

## Layout

- `src/`: the client mod, one tree for every loader and version ([Stonecutter](https://stonecutter.kikugie.dev/))
- `common/`: the networking core and the EasyTier natives
- `paper/`: the Paper plugin, a separate Gradle build
- `site/`: the website

## Build

CI builds every version, so build only the ones you're working on. Install the JDK each one targets
(17, 21 or 25), plus JDK 8 for Forge. Gradle can't download them for you, so a missing JDK fails the build.

```bash
./gradlew chiseledBuild -Petmc.nodes='1\.21\.10-.*'   # -> versions/<node>/build/libs/
./gradlew -p paper build                              # -> paper/build/libs/
```

Put your proxy and JDK 8 path in `~/.gradle/gradle.properties`, not the repo's `gradle.properties`.

## Test

```bash
./gradlew -p paper test   # core tests
cd site && bun test       # site tests
```

The `etmc://` link and TOML output exist twice: in `JoinCode` and `Toml`, and in `site/src/lib/genConfig.ts`.
Change both together, and keep the vectors in `WireFormatTest` and `genConfig.test.ts` in sync.

## Natives

Rebuild natives with the `natives.yml` workflow. `easytier-update.yml` also proposes updates weekly.

## Website

Run `bun run dev` in `site/` to work on it, and `bun run deploy` to publish it.

## Release

Push a `v*` tag.
