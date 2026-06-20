# Multi-version migration (working doc — branch `feature/multiversion`)

Goal: support **MC 1.17 → latest (26.2)**, **most compatibility**, all loaders where each exists,
built via **Stonecutter** (`dev.kikugie.stonecutter`). CI builds everything.

> **1.16 is excluded** unless explicitly re-approved: it runs on **Java 8**, which can't run the
> records / switch-expressions / pattern-matching the codebase uses. Including it means down-leveling
> the entire mod to Java 8 (+ ForgeGradle 5 + pre-`GuiGraphics` GUI). 1.17 (Java 16) has records, so
> everything from 1.17 up is feasible without gutting the code.

## Target matrix (Stonecutter version × loader)

| MC | Java | FFI backend | Fabric | NeoForge | Forge | Paper |
|------|------|------|:--:|:--:|:--:|:--:|
| 1.17.1 | 17 | JNA | ✅ | — | ✅ | ✅ |
| 1.18.2 | 17 | JNA | ✅ | — | ✅ | ✅ |
| 1.19.4 | 17 | JNA | ✅ | — | ✅ | ✅ |
| 1.20.1 | 17 | JNA | ✅ | ✅ | ✅ | ✅ |
| 1.20.6 | 21 | FFM | ✅ | ✅ | ✅ | ✅ |
| 1.21.10 | 21 | FFM | ✅ | ✅ | ✅ | ✅ |
| 26.x | 25 | FFM (unobf) | ⏳ | ⏳ | ⏳ | ⏳ |

> **26.x** ships **unobfuscated** (no mappings) — official names, shares the mojmap path — but is **not
> on the build's maven yet** (not buildable until it publishes). **1.21.11** exists but is deferred
> (its `NetworkingBackend` connect refactor breaks the channel-swap mixin remap on fresh mappings);
> **1.21.10** is the verified ceiling.

(1.20 split into 1.20.1/Java-17 and 1.20.6/Java-21 for the Java-version boundary. NeoForge starts at
1.20.1. Manifest version ranges widen each row to cover its patch siblings.)

## FFI backend selection (done — stages 1–2)
- `EasyTier` is a backend interface; `EasyTier.load()` → `FfmEasyTier` (java.lang.foreign, Java 19+),
  else `JnaEasyTier` (JNA, Java 16/17 — works on Java 8+ too). `byte[]` data plane, no caller coupling.
- JNA is `compileOnly` on FFM rows; the JNA rows (1.17–1.20.1) **bundle** it (per-version build).

## Per-version code variants (Stonecutter `//? if` comments)
- **GUI**: `DrawContext` (1.20+) vs `MatrixStack` + `DrawableHelper` (1.17–1.19) — funnelled through
  `client/Gfx.java` so screens/HUD call `Gfx.centered/text/fill` and only `Gfx` carries the split.
  Pre-1.20 `drawCenteredTextWithShadow` only has the **`OrderedText`** overload → `Gfx.centered`
  passes `t.asOrderedText()` (works 1.17–1.19; 1.19.4 has both overloads).
- **Text factories**: `Text.literal/translatable` are **1.19+** only; 1.16–1.18 use `new LiteralText` /
  `new TranslatableText`. Funnelled through `client/Txt.java` (`Txt.literal/translatable`).
- **Buttons**: `ButtonWidget.builder(...).dimensions(...).build()` is **1.19.4+**; older uses the
  `new ButtonWidget(x,y,w,h,text,onPress)` ctor. `client/Ui.java` mirrors the builder fluent shape so
  call sites only change `ButtonWidget.builder(` → `Ui.button(`.
- **Screen close hook**: `Screen.close()` is **1.18+**; 1.17.1 is `onClose()`. Each screen keeps a
  plain `close()` and adds a guarded `onClose()` delegate for `<1.18` so callers are untouched.
- **Client commands**: `…client.command.v2` (1.19+, registered via `ClientCommandRegistrationCallback`)
  vs `…v1` (1.16–1.18, registered on the static `ClientCommandManager.DISPATCHER`). Guarded in
  `EtmcCommands` (imports) + `EtmcClient` (registration).
- **Gson** (shared `common/`, no Stonecutter preprocessing): `JsonParser.parseString` is 2.8.6+;
  1.17/1.18 bundle older Gson → use the universal instance `new JsonParser().parse(json)`.
- **Mixins**: `ClientConnection.connect` is the 2-arg `(InetSocketAddress, boolean)` form `<1.20` vs the
  3-arg `→ChannelFuture` helper `>=1.20`; `ServerInfo`/`ServerList.add` arity also shifts (3-tier guard
  in `McNet`). NeoForge/Forge use official names; SRG vs official on old Forge.
- **Mappings**: yarn (Fabric ≤1.21) / mojmap (Neo/Forge, Fabric 26.x unobf); parchment where useful.
- **26.x**: unobfuscated — Fabric also on official names; mixin configs use official names.

## Loader toolchains per era
- Fabric: Loom (all). NeoForge: ModDevGradle (1.20.1+). Forge: ForgeGradle 7 (1.21), FG6 (1.18–1.20),
  FG5 (1.17). Paper: plain java + paper-api.

## Stages
1. ✅ FFI backend interface (`8602f31`)
2. ✅ JNA backend (`aca2705`)
3. ⏳ Stonecutter harness: settings + controller, migrate **1.21.10** first (green), keep branch building.
4. ⏳ Add versions one at a time: 1.20.6 → 1.20.1 → 1.19.4 → 1.18.2 → 1.17.1 → 26.2, fixing each
   version's GUI/mixin/mapping breaks; bundle JNA on the Java-16/17 rows.
5. ⏳ Per-version loader metadata + version ranges (fabric.mod.json, mods.toml, neoforge.mods.toml, plugin.yml).
6. ⏳ CI: `natives.yml` already version-agnostic; extend `build.yml`/`release.yml` to the Stonecutter
   matrix with the right JDK (16/17/21/25) per target.

## Confirmed Stonecutter blueprint (0.9.2, from rotgruengelb template — docs site is anti-scrape-blocked, used raw GitHub)
```kotlin
// settings.gradle.kts
plugins { id("dev.kikugie.stonecutter") version "0.9.2" }
stonecutter {
  create(rootProject) {
    fun match(v: String, vararg loaders: String) =
      loaders.forEach { version("$v-$it", v).buildscript = getBuildscript(it, v) }
    match("26.2",    "fabric", "neoforge", "forge")
    match("1.21.10", "fabric", "neoforge", "forge")
    // ... 1.20.6 / 1.20.1 / 1.19.4 / 1.18.2 / 1.17.1 ...
    vcsVersion = "1.21.10-fabric"
  }
}
// getBuildscript: fabric+1.x -> build.fabric-o.gradle.kts (yarn); fabric+26.x -> build.fabric-m.gradle.kts (mojmap/unobf)
```
- `create(rootProject)` ⇒ **single source tree at root** + `build.<loader>.gradle.kts` per loader; Stonecutter generates `versions/<v>-<loader>/` nodes. Controller `stonecutter.gradle.kts` sets active version + `parameters { swaps/constants }`.
- pluginManagement repos add: `maven.kikugie.dev/releases` + `/snapshots`, fabric, neoforged, parchmentmc.
- **Implication**: the current `fabric/ neoforge/ forge/ paper/ common/ mc-common/` split must merge into one root `src/` with `//? if fabric {` / `//? if >=1.20 {` preprocessor guards. Big restructure — do it incrementally, Fabric branch first.

### Stage 3.5: yarn+mojmap merge for multi-version NeoForge/Forge (in progress)
Stonecutter processes ONE root `src/`, so loaders sharing it means merging the yarn (`src/`) and
mojmap (`mc-common/`) client trees into one tree with `//? if fabric` guards (loader constants set per
node from the id suffix). The facades (`Txt`/`Ui`/`Gfx`) absorb text/button/draw; per-file guards
cover the rest. Use **flat** `fabric && >=x` conditions (NOT nested) — a `//? if fabric {` block that
gets commented when `fabric=false` must not contain `/* */`.

- ✅ Done (merged, Fabric-green, committed): loader constants (controller `parameters`); `Txt`/`Ui`/
  `Gfx` mojmap branches; `EtmcManager` (+ slf4j LOGGER, supersedes `EtmcClientCore`); `ModConfig`
  (supersedes `McConfig`); `EtmcHud`; `McNet`.
- ⏳ Remaining client files to merge (mojmap from `mc-common/`): `EtmcCommands` (~35 guards —
  `FabricClientCommandSource`/`ClientCommandManager` vs `CommandSourceStack`/`Commands`, `sendError`/
  `sendFeedback` vs `sendFailure`/`sendSuccess`); the 8 screens; `EtmcKey` (mojmap keybind, new in
  src/, guarded `//? if !fabric`); mixins `ClientConnectionMixin`↔`ConnectionMixin`,
  `ConnectScreenMixin` (yarn+mojmap targets), `ServerAddressMixin`, `AddServerScreenMixin`↔
  `ManageServerScreenMixin`, `DirectConnectScreenMixin`↔`DirectJoinServerScreenMixin`.
- ⏳ Entry points: guard `EtmcClient` `//? if fabric`; move `EtmcNeoForge`/`EtmcForge` into guarded
  src/ (or loader srcDirs), retargeting to `EtmcManager` + guarding `ResourceLocation.fromNamespaceAndPath`.
- ⏳ Infra: `build.neoforge.gradle.kts` / `build.forge.gradle.kts` node scripts; settings loader nodes
  (`1.21.10-neoforge`, `1.20.6-neoforge`, forge eras with FG7/6/5); per-loader mixin config + mods.toml;
  then delete the standalone `neoforge/`/`forge/` subprojects + `mc-common/`.
- ⏳ Verify: compile each loader node; extend CI matrix; Forge's FG5/6/7-per-era is the hardest.

### Stage 3 order (Fabric-first, per user)
- 3a: ✅ Stonecutter harness, **Fabric 1.21.10**, green + committed.
- 3b: ✅ Fabric **1.20.6, 1.20.1, 1.19.4, 1.18.2, 1.17.1** all build green (compile + remapJar, no remap
  warnings) — `Gfx`/`Txt`/`Ui` facades + command-v1/v2, `onClose`, `ServerList`, Gson, connect-mixin
  guards. JNA bundled on the Java-17 rows (1.17.1–1.20.1). **Runtime not yet verified** (compile-only).
- 3c: ⏳ Fabric **26.x** — Mojang ships 26.x **unobfuscated**, so there is **no mappings artifact**
  (that's why Fabric meta's yarn list stops at 1.21.11). A 26.x node uses a **no-mappings (official
  names) Loom variant** and its code uses official names → it shares the **mojmap/`mc-common` naming**,
  not yarn `src/`. **Blocked: 26.x isn't published to the build's maven yet** (`/versions/game` had no
  `26.*` on 2026-06-20) — can't build until the game/loader artifacts land. Java 25 toolchain.
- **1.21.11 deferred**: it's the newest *buildable* version, but it refactored
  `ClientConnection.connect` arg2 `boolean`→`NetworkingBackend`; the channel-swap `@Redirect` descriptor
  won't tiny-remap against the very fresh yarn `1.21.11+build.6` mappings (the new class's intermediary
  mapping is inconsistent), so the mixin would silently not apply → etmc:// joining broken. Kept
  **1.21.10 as the verified ceiling** (clean remap, zero warnings) rather than ship a broken node.
  Revisit when 1.21.11 mappings stabilize (or try refmap mode for that node).
- then NeoForge, Forge, Paper branches.
- Per-Fabric-version coordinates resolved (yarn build, fabric-loader, fabric-api) via fabricmc meta/maven.
  Note: 1.17.1 + 1.18.2 use the **Java 17** toolchain (MC 1.17 needs Java 16+, 17 runs it fine).

## Verification reality
Local JDKs 16?/17/21/25 cover most runtime checks, but each Neo/Forge version downloads a toolchain
(slow), 26.2 postdates training, and the JNA runtime path only exercises on Java 16/17 builds. Off-target
builds are verified by building each row; nothing here is assumed-green without a build.
