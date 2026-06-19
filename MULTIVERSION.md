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
| 1.17.1 | 16 | JNA | ✅ | — | ✅ | ✅ |
| 1.18.2 | 17 | JNA | ✅ | — | ✅ | ✅ |
| 1.19.4 | 17 | JNA | ✅ | — | ✅ | ✅ |
| 1.20.1 | 17 | JNA | ✅ | ✅ | ✅ | ✅ |
| 1.20.6 | 21 | FFM | ✅ | ✅ | ✅ | ✅ |
| 1.21.10 | 21 | FFM | ✅ | ✅ | ✅ | ✅ |
| 26.2 | 25 | FFM (unobf) | ✅ | ✅ | ✅ | ✅ |

(1.20 split into 1.20.1/Java-17 and 1.20.6/Java-21 for the Java-version boundary. NeoForge starts at
1.20.1. Manifest version ranges widen each row to cover its patch siblings.)

## FFI backend selection (done — stages 1–2)
- `EasyTier` is a backend interface; `EasyTier.load()` → `FfmEasyTier` (java.lang.foreign, Java 19+),
  else `JnaEasyTier` (JNA, Java 16/17 — works on Java 8+ too). `byte[]` data plane, no caller coupling.
- JNA is `compileOnly` on FFM rows; the JNA rows (1.17–1.20.1) **bundle** it (per-version build).

## Per-version code variants (Stonecutter `//? if` comments)
- **GUI**: `GuiGraphics` (1.20+) vs `MatrixStack`/`PoseStack` (1.17–1.19) in screens + HUD.
- **Mixins**: `ConnectScreen` / `ServerAddress` / `Connection` method signatures differ per version
  (yarn names on Fabric; official names on NeoForge/Forge; SRG vs official on old Forge).
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

## Verification reality
Local JDKs 16?/17/21/25 cover most runtime checks, but each Neo/Forge version downloads a toolchain
(slow), 26.2 postdates training, and the JNA runtime path only exercises on Java 16/17 builds. Off-target
builds are verified by building each row; nothing here is assumed-green without a build.
