# etmc

EasyTier P2P multiplayer, in-game. MCJE — **Fabric, NeoForge, Forge** (client) and **Paper** (server).

Host your singleplayer world or join a friend over a peer-to-peer [EasyTier](https://github.com/EasyTier/EasyTier)
mesh — **no port-forwarding, no TUN driver, no admin rights**. etmc embeds the EasyTier FFI native
library and runs it in **no-TUN mode**, bridging vanilla Minecraft's loopback LAN socket to the mesh
entirely in userspace.

## Status

All targeting **Minecraft 1.21.10** (Java 21):

| Module | Loader | Role | Toolchain |
|---|---|---|---|
| `fabric/` | Fabric | client (host/join/connect, GUI, HUD, `/etmc`) | Fabric Loom (yarn) |
| `neoforge/` | NeoForge | client (same features) | ModDevGradle (mojmap) |
| `forge/` | MinecraftForge | client (same features) | ForgeGradle 7 (mojmap) |
| `paper/` | Paper/Bukkit | **server** — exposes a dedicated server over the mesh | paper-api |

The platform-independent FFI + networking lives in `common/` (shared by all four); the Mojmap client
UI is shared between NeoForge and Forge in `mc-common/`. The FFI + networking core is verified against
the real `easytier_ffi` library (config parse/run, instance info, virtual-IP decode, data-plane bind
all pass a standalone smoke test on Windows and Linux). End-to-end multiplayer between two peers needs
a running relay and a second machine, which isn't part of the automated checks.

## How it works

```
 Host                                          Joiner
 ────                                          ──────
 Minecraft "Open to LAN" (127.0.0.1:LAN)       Minecraft client
        ▲                                             │  vanilla netty pipeline
        │ loopback socket                             ▼
   HostShare ── forwards each stream            EtmcChannel  (no socket, no port)
        │                                             │  reads/writes the
        ▼                                             ▼  EasyTier data plane
   EasyTier (no_tun, 10.126.126.1) ──── mesh ──── EasyTier (no_tun, dhcp)
                        via your relay
```

Both peers run an EasyTier instance in no-TUN mode and meet on the mesh through your relay — no TUN
device, no forwarded port. The host takes a fixed virtual IP (`10.126.126.1`) so joiners always know
where to reach it; joiners use DHCP.

- **Host**: `HostShare` accepts each peer stream off the mesh and forwards it to the local "Open to
  LAN" server on `127.0.0.1:<lanPort>`.
- **Join via `etmc://` link** (the link/Direct-Connect flow): the connection rides an **`EtmcChannel`**
  — a netty transport whose bytes go straight through the EasyTier data plane (`data_plane_tcp_*`), so
  vanilla networking (and mods like ViaFabricPlus, on top of it) run over the mesh with **no loopback
  socket and no port**.
- **Join via code / config URL**: `JoinProxy` opens a loopback listener and points Minecraft at
  `127.0.0.1:<port>` as if it were a LAN server, bridging each local connection to the host's virtual
  IP over the mesh.
- A **join code** (`ETMC1:…`) carries the network name, secret, relay(s) and host address — share it
  with a friend, or publish an `etmc://` link / config URL.

The Foreign Function & Memory API (`java.lang.foreign`) is reached through a small **reflection
facade** (`ffi/Panama.java`) so the jar stays plain Java 21 bytecode and runs on Java 21+ without
`--enable-preview`.

## Using it (in game)

1. Open the menu with the **G** key (rebindable, under *Controls → Misc*) or `/etmc menu`.
2. **Settings → relays**: add your EasyTier relay node, e.g. `tcp://my.relay:11010` (required — etmc
   ships no public relay).
3. **Host this world** → pick a network name (+ optional secret) → share the join code shown.
4. Friends: **Join with a code** → paste → connect.

Commands: `/etmc host <network> [secret]`, `/etmc join <code>`, `/etmc connect <url> [ip:port]`,
`/etmc status`, `/etmc invite` (copies the code), `/etmc leave`, `/etmc relay add|list|clear`,
`/etmc hud`.

### Connect to a server from a config URL

A server admin can publish a single config file over HTTP(S) so players join with just a link
(**Connect via config URL** in the menu, or `/etmc connect <url>`). The file is a normal EasyTier
TOML config (network, relay, ip) plus an optional etmc block naming the in-mesh Minecraft server:

```toml
# EasyTier config the client joins with
dhcp = true
[network_identity]
network_name = "my-smp"
network_secret = "shared-secret"
[[peer]]
uri = "tcp://relay.example.com:11010"

# where the Minecraft server lives on the mesh (etmc extension; stripped before EasyTier sees it)
[etmc]
server = "10.144.144.1:25565"
label  = "My SMP"
```

etmc fetches it, forces `no_tun`, joins the mesh, and tunnels to `server`. If the file omits
`[etmc] server`, the player fills the Server field / passes `ip:port` to the command. An `ETMC1:…`
join code at the URL works too. The target server just needs to be reachable on the EasyTier
network — it does **not** need to run etmc.

## Mod compatibility (ViaFabricPlus, etc.)

etmc is built to coexist with other mods, including protocol-translation mods like **ViaFabricPlus**:

- **Surgical mixins.** etmc adds only a few small client mixins, all confined to the multiplayer
  connect path: they let Minecraft accept an `etmc://` address (instead of rejecting it as an
  unparseable host) and route it onto the mesh, and widen the Add Server / Direct Connect field so a
  long link isn't truncated. Ordinary connections are left untouched.
- **Transparent TCP relay.** Code-based joins are bridged at the raw byte level on a loopback socket,
  *below* the Minecraft protocol. ViaFabricPlus translates in the client's netty pipeline and etmc
  forwards the already-translated bytes verbatim, so the two compose — VFP auto-detects the real
  server version by pinging through the loopback proxy.
- **Stable loopback port.** Set `joinLocalPort` in `config/etmc.json` to a fixed port so VFP's
  per-server (per-address) version setting persists across sessions (default `0` = ephemeral).

## Building

Requires JDK 21 (the Gradle toolchain is pinned to 21) plus the EasyTier FFI native library for each
platform you want to ship. Natives are git-ignored (they're large, ~16–20 MB each) and live under
`src/main/resources/natives/<os>-<arch>/`:

| Platform | File |
|---|---|
| `windows-x86_64` | `easytier_ffi.dll` |
| `linux-x86_64`, `linux-aarch64` | `libeasytier_ffi.so` |
| `macos-x86_64`, `macos-aarch64` | `libeasytier_ffi.dylib` |

The native build needs Rust (pinned 1.95) + `protoc` + the **mold** linker. We build with
`--features easytier/full` (all transports/connectors + crypto backends). `full` pulls in **vendored
OpenSSL**, which additionally needs **perl** (everywhere) and **NASM** (on Windows) at build time.

```bash
# Windows (x86_64) — from a Windows shell with Rust + protoc + perl + nasm
cd /path/to/EasyTier
PROTOC=.../protoc.exe cargo build -p easytier-ffi --release --features easytier/full
#   -> target/release/easytier_ffi.dll

# Linux x86_64 (native) and aarch64 (cross) — e.g. in WSL Debian
sudo apt install build-essential protobuf-compiler cmake clang libclang-dev perl \
                 mold lld gcc-aarch64-linux-gnu g++-aarch64-linux-gnu
cargo build -p easytier-ffi --release --features easytier/full                 # linux-x86_64
CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=aarch64-linux-gnu-gcc \
CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_RUSTFLAGS=-Clink-arg=-fuse-ld=lld \
CC_aarch64_unknown_linux_gnu=aarch64-linux-gnu-gcc \
AR_aarch64_unknown_linux_gnu=aarch64-linux-gnu-ar \
cargo build -p easytier-ffi --release --features easytier/full --target aarch64-unknown-linux-gnu

# macOS (x86_64 / aarch64) — must be built ON a Mac (Apple SDK required)
cargo build -p easytier-ffi --release --features easytier/full --target aarch64-apple-darwin
```

Drop each output into the matching `natives/<os>-<arch>/` folder, then build the jar:

```bash
cd /path/to/etmc
./gradlew copyNatives     # convenience: copies the Windows dll from ../EasyTier/target/release
./gradlew build           # -> build/libs/etmc-<version>.jar
```

Override the EasyTier checkout location with `-Peasytier_repo=/path/to/EasyTier`. The jar bundles
whatever natives are present; at runtime the loader extracts the one matching the player's OS/arch.

### Local machine config

Machine-specific Gradle settings — your outbound **proxy** and the **JDK 8** path ForgeGradle's
mavenizer needs — belong in your **global** `~/.gradle/gradle.properties`, *not* in the committed
`gradle.properties` (kept clean so CI works). For example:

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=10808
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=10808
org.gradle.java.installations.paths=/path/to/jdk8
systemProp.org.gradle.java.installations.paths=/path/to/jdk8
```

### CI

Three workflows:

- **`build.yml`** — runs **only on changes that actually need a build** (`paths-ignore` skips
  docs/site-only commits like `site/**`, `*.md`). It assembles all four loader jars from the natives
  **committed** in the repo and never compiles natives itself — so a routine push is a single fast
  `assemble jars` job, with no Rust build and no skipped native matrix.
- **`natives.yml`** — builds the EasyTier FFI natives (matrix over Windows / Linux x64+arm64 / macOS
  x64+arm64). It skips any platform already committed under `natives/<os>-<arch>/` and caches the rest
  two ways (built-native cache keyed on EasyTier ref + `Cargo.lock`; `rust-cache` for misses). Run it
  from the Actions tab (`workflow_dispatch`, optional `easytier_ref`); it's also reused by `release.yml`.
- **`release.yml`** — push a tag like `v0.1.0`: it builds any natives missing at the **release commit**
  (reusing whatever's committed there), assembles the jars with them, and attaches every jar to the
  GitHub Release. `workflow_dispatch` runs the same build without publishing (dry run).

Gradle deps are cached via `gradle/actions/setup-gradle`.

## License

GPL-3.0-or-later (see `LICENSE`).
