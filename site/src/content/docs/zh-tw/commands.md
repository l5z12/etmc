---
# Translated from src/content/docs/en/commands.md by scripts/translate.ts. Edit the English, then run `bun run translate`.
title: 指令
description: 所有 /etmc 指令。
---

## 遊戲內

這些指令大多也能在選單中找到，選單以 <kbd>G</kbd> 開啟。你可以在 *按鍵設定* 中變更這個按鍵。較舊的 Fabric 版本沒有這些指令，請改用選單。

| 指令 | 作用 |
| --- | --- |
| `/etmc menu` | 開啟 etmc 選單。 |
| `/etmc host [network] [secret]` | 開放目前的世界。省略網路名稱時，會沿用上一次的網路和金鑰重新開放，這樣你分享過的連結仍然有效。新的網路會隨機產生金鑰，除非你自己指定。 |
| `/etmc invite` | 複製你的邀請連結。 |
| `/etmc join <link>` | 用 etmc:// 或 etmc://v1/config/ 連結加入。 |
| `/etmc connect <url> [ip:port]` | 用設定檔 URL 加入。 |
| `/etmc status` | 顯示你的連線狀態和玩家。 |
| `/etmc leave` | 離開網路。 |
| `/etmc relay add\|list\|clear` | 管理中繼。 |
| `/etmc hud` | 顯示或隱藏狀態顯示。 |

## Paper 伺服器

這些需要管理員權限（`etmc.admin`）。

| 指令 | 作用 |
| --- | --- |
| `/etmc status` | 顯示伺服器的連線狀態和玩家。 |
| `/etmc link` | 顯示邀請連結。 |
| `/etmc config` | 在外掛資料夾中寫入玩家設定檔 join-config.toml。 |
| `/etmc reload` | 重新載入 config.yml 並重新連線。 |
