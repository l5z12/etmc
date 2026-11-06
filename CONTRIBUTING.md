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

## Natives

Rebuild natives with the `natives.yml` workflow. `easytier-update.yml` also proposes updates weekly.

## Website

Run `bun run dev` in `site/` to work on it, and `bun run deploy` to publish it.

## Release

Push a `v*` tag.
