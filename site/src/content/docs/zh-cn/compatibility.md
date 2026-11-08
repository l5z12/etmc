---
# Translated from src/content/docs/en/compatibility.md by scripts/translate.ts. Edit the English, then run `bun run translate`.
title: 模组兼容性
description: etmc 与其他模组如何共存。
---

etmc 只改变连接的传输方式，不会改动 Minecraft 的数据包，因此在游戏和其他模组看来，这就是一个普通的服务器连接。

## ViaFabricPlus

etmc 可与 ViaFabricPlus 配合使用，包括其自动版本检测，无需任何设置。etmc 并不依赖它。

如果你想在使用配置 URL 加入时，让在 ViaFabricPlus 中强制指定的版本在多次会话之间保持不变，请把 `config/etmc.json` 中的 `joinLocalPort` 设为一个固定端口。ViaFabricPlus 按地址保存设置，否则 etmc 每次都会选一个新端口。
