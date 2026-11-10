---
# Translated from src/content/docs/en/getting-started.md by scripts/translate.ts. Edit the English, then run `bun run translate`.
title: 開始使用
description: 安裝 etmc、新增中繼，然後開始遊玩。
---

無論是開放世界還是加入世界，所有玩家都需要安裝 etmc。

## 安裝

1. 安裝 [Fabric](https://fabricmc.net/)、[NeoForge](https://neoforged.net/) 或 [Forge](https://files.minecraftforge.net/)。
2. 從[下載](/downloads)頁面下載對應你的模組載入器和 Minecraft 版本的 etmc jar。
3. 把它放進你的 `mods` 資料夾。使用 Fabric 的話，還要加上 [Fabric API](https://modrinth.com/mod/fabric-api)。

## 新增中繼

中繼能幫玩家互相找到對方，並在無法直連時轉送遊戲流量。任何 EasyTier 節點都可以，你可以從這份[公共節點列表](https://ruixuan.online/uptime/easytier)挑一個免費的，或自己架一個。

1. 按下 <kbd>G</kbd>、在遊戲選單或多人遊戲畫面點選 **etmc**，或執行 `/etmc menu`。
2. 開啟 **設定與中繼**，新增你的中繼，例如 `tcp://my.relay:11010`。**尋找中繼**會開啟公共節點列表。如果跳過這一步，etmc 會在你開放世界時要你填一個。

如果你是透過邀請連結加入，可以跳過這一步，因為連結中已包含中繼。

<div class="note">公共節點是與陌生人共用的。使用相同網路名稱和金鑰的人會進入同一個網路，所以請保留 etmc 給你的隨機名稱和金鑰。</div>

## 遊玩

接下來，[開放世界](/docs/hosting)或[加入世界](/docs/joining)。
