---
title: Getting started
description: Install etmc, add a relay and start playing.
---

Everyone who plays needs etmc installed, whether they're hosting or joining.

## Install

1. Install [Fabric](https://fabricmc.net/), [NeoForge](https://neoforged.net/) or
   [Forge](https://files.minecraftforge.net/).
2. Download the etmc jar for your loader and Minecraft version from the [Download](/downloads) page.
3. Put it in your `mods` folder. On Fabric, add [Fabric API](https://modrinth.com/mod/fabric-api) too.

## Add a relay

A relay helps players find each other, and carries the game when they can't connect directly. Any EasyTier node
works, so you can pick a free one from this [list of public nodes](https://ruixuan.online/uptime/easytier) or run
your own.

1. Press <kbd>G</kbd>, click **etmc** in the pause menu or on the Multiplayer screen, or run `/etmc menu`.
2. Open **Settings** and add your relay, such as `tcp://my.relay:11010`. **Find a relay** opens the list of public
   nodes. If you skip this, etmc asks for one when you host.

If you're joining with an invite link, you can skip this step, because the link includes the relay.

<div class="note">Public nodes are shared with strangers. Anyone using the same network name and secret lands on
the same network, so keep the random ones etmc gives you.</div>

## Play

Next, [host a world](/docs/hosting) or [join one](/docs/joining).
