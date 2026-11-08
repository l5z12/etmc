---
# Translated from src/content/docs/en/commands.md by scripts/translate.ts. Edit the English, then run `bun run translate`.
title: 命令
description: 所有 /etmc 命令。
---

## 游戏内

这些命令大多也能在菜单中找到，按 <kbd>G</kbd> 即可打开菜单。你可以在*按键控制*中修改这个按键。最早的 Fabric 版本没有这些命令，请改用菜单。

| 命令 | 作用 |
| --- | --- |
| `/etmc menu` | 打开 etmc 菜单。 |
| `/etmc host [network] [secret]` | 开放当前世界。不填网络名称，就会用原来的密钥再次开放上一个网络，这样你分享过的链接仍然有效。新网络会随机生成密钥，除非你指定一个。 |
| `/etmc invite` | 复制你的邀请链接。 |
| `/etmc join <link>` | 使用 etmc:// 或 etmc://v1/config/ 链接加入。 |
| `/etmc connect <url> [ip:port]` | 使用配置 URL 加入。 |
| `/etmc status` | 显示你的连接状态和玩家。 |
| `/etmc leave` | 离开网络。 |
| `/etmc relay add\|list\|clear` | 管理中继。 |
| `/etmc hud` | 显示或隐藏左上角状态显示。 |

## Paper 服务器

这些命令需要 OP 权限（`etmc.admin`）。

| 命令 | 作用 |
| --- | --- |
| `/etmc status` | 显示服务器的连接状态和玩家。 |
| `/etmc link` | 显示邀请链接。 |
| `/etmc config` | 将一份玩家配置写入插件文件夹中的 join-config.toml。 |
| `/etmc reload` | 重新加载 config.yml 并重新连接。 |
