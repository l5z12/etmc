---
# Translated from src/content/docs/en/getting-started.md by scripts/translate.ts. Edit the English, then run `bun run translate`.
title: 快速上手
description: 安装 etmc、添加中继，然后开始游玩。
---

无论你是开放世界还是加入游戏，每个玩家都需要安装 etmc。

## 安装

1. 安装 [Fabric](https://fabricmc.net/)、[NeoForge](https://neoforged.net/) 或 [Forge](https://files.minecraftforge.net/)。
2. 从 [Releases](https://github.com/L5Z12/etmc/releases) 下载适用于你的加载器和 Minecraft 版本的 etmc jar。
3. 把它放进 `mods` 文件夹。使用 Fabric 的话，还要添加 [Fabric API](https://modrinth.com/mod/fabric-api)。

## 添加中继

中继帮助玩家找到彼此，并在无法直连时转发游戏流量。任何 EasyTier 节点都能用，所以你可以从这份[公共节点列表](https://ruixuan.online/uptime/easytier)中挑一个免费的，也可以自己搭建。

1. 按 <kbd>G</kbd>，在游戏菜单或“多人游戏”界面点击 **etmc**，或运行 `/etmc menu`。
2. 打开 **设置与中继**，添加你的中继，例如 `tcp://my.relay:11010`。点击 **查找中继** 会打开公共节点列表。如果跳过这一步，etmc 会在你开放世界时要求填写中继。

如果你用邀请链接加入，可以跳过这一步，因为链接中已经包含中继。

<div class="note">公共节点由许多互不相识的人共用。任何使用相同网络名称和密钥的人都会进入同一个网络，因此请保留 etmc 为你随机生成的名称和密钥。</div>

## 开始游戏

接下来，[开放世界](/docs/hosting)或[加入游戏](/docs/joining)。
