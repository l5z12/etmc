# Contributing

## Layout

- `mod/`: the client mod, one source tree for every loader and version, built with
  [Stonecutter](https://stonecutter.kikugie.dev/)
- `common/`: the networking core, shared by the mod and the plugin
- `paper/`: the Paper plugin
- `site/`: the website

## Build and test

CI builds every version, so build only the one you're working on. Install the JDK it targets.

```bash
./gradlew chiseledBuild -Petmc.nodes='1\.21\.10-.*'   # -> mod/versions/<node>/build/libs/
./gradlew -p paper build                              # -> paper/build/libs/
./gradlew -p common test
cd site && bun test
```

The `etmc://` link is built twice: in `JoinCode` and `Toml`, and in `site/src/lib/genConfig.ts`. Change both, and
keep `WireFormatTest` and `genConfig.test.ts` in sync.

## Website

Run `bun run dev` in `site/`. Write only English, in `src/content/docs/en/` and `src/i18n/en.json`, then run
`bun run translate` to update the Chinese.

## Pull requests

Title a pull request as a [Conventional Commit](https://www.conventionalcommits.org/), such as `feat: add X` or
`fix(site): Y`. A check fails until it is.

## Release

Push a `v*` tag.
