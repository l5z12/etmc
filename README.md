<img src="site/public/icon-512.png" alt="" width="96" height="96" align="right">

# etmc

Play Minecraft with friends over a peer-to-peer [EasyTier](https://github.com/EasyTier/EasyTier) mesh:
**no port forwarding, no virtual network adapter, no admin rights**. Host your singleplayer world or a
Paper server, then share one link.

**[Download](https://etmc.l5z12.dev/downloads)** ·
**[Documentation](https://etmc.l5z12.dev/docs/getting-started)** ·
[简体中文](https://etmc.l5z12.dev/zh-cn/docs/getting-started) ·
[繁體中文](https://etmc.l5z12.dev/zh-tw/docs/getting-started)

etmc runs on Fabric, NeoForge and Forge clients, and on Paper servers. Everyone who plays needs it
installed.

## Quick start

1. Put the jar for your loader and Minecraft version in `mods/`. Fabric also needs
   [Fabric API](https://modrinth.com/mod/fabric-api).
2. Press **G**, or click **etmc** in the pause menu, to open the menu.
3. Under **Settings & relays**, add an EasyTier relay such as `tcp://my.relay:11010`. Any EasyTier node
   works, so pick a free one from this [list of public nodes](https://ruixuan.online/uptime/easytier)
   (**Find a relay** opens it), or run your own.
4. Choose **Host this world**, then **Start hosting**. etmc copies your `etmc://` invite link, so
   send it to your friends.
5. Friends paste the link into **Add Server / Direct Connect**, or use **Join with a link** in the etmc
   menu.

> [!WARNING]
> Your secret isn't protected. It's saved as plain text, and anyone with your invite link can read it.
> Keep it random, and never use a password you use anywhere else.

## How it works

Each player runs EasyTier inside Minecraft. Players find each other through a relay, then connect
directly when their networks allow it. If they can't, traffic goes through the relay.

Everything stays inside the game, so etmc needs no driver or admin rights. Other mods, including
ViaFabricPlus, keep working.

## Learn more

- [Hosting](https://etmc.l5z12.dev/docs/hosting) and [joining](https://etmc.l5z12.dev/docs/joining)
- [Commands](https://etmc.l5z12.dev/docs/commands)
- [Connecting with a config URL](https://etmc.l5z12.dev/docs/connect-url)
- [Mod compatibility](https://etmc.l5z12.dev/docs/compatibility)

To build etmc or work on it, see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

GPL-3.0-or-later (see `LICENSE`).
