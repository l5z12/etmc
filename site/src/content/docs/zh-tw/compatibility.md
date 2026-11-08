---
# Translated from src/content/docs/en/compatibility.md by scripts/translate.ts. Edit the English, then run `bun run translate`.
title: 模組相容性
description: etmc 如何與其他模組搭配運作。
---

etmc 只改變連線的傳輸方式，不會更動 Minecraft 的封包，因此對遊戲和其他模組來說，這就是一般的伺服器連線。

## ViaFabricPlus

etmc 可與 ViaFabricPlus 搭配運作，包括它的自動版本偵測，完全不需要任何設定。etmc 並不強制要求安裝它。

若你想在透過設定檔 URL 加入時，讓在 ViaFabricPlus 中強制指定的版本在多次連線之間保留，請將 `config/etmc.json` 裡的 `joinLocalPort` 設為固定連接埠。ViaFabricPlus 會依位址儲存設定，否則 etmc 每次都會挑選新的連接埠。
