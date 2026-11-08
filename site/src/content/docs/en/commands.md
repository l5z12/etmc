---
title: Commands
description: Every /etmc command.
---

## In game

Most of these are also in the menu, which opens with <kbd>G</kbd>. You can change that key under *Controls*. The
oldest Fabric versions don't have these commands, so use the menu there.

| Command | What it does |
| --- | --- |
| `/etmc menu` | Open the etmc menu. |
| `/etmc host [network] [secret]` | Host the current world. Leave the network out to host the last one again, with its secret, so links you've shared still work. A new network gets a random secret unless you give one. |
| `/etmc invite` | Copy your invite link. |
| `/etmc join <link>` | Join with an etmc:// or etmc://v1/config/ link. |
| `/etmc connect <url> [ip:port]` | Join with a config URL. |
| `/etmc status` | Show your connection and players. |
| `/etmc leave` | Leave the network. |
| `/etmc relay add\|list\|clear` | Manage your relays. |
| `/etmc hud` | Show or hide the status overlay. |

## Paper server

These need operator permission (`etmc.admin`).

| Command | What it does |
| --- | --- |
| `/etmc status` | Show the server's connection and players. |
| `/etmc link` | Show the invite link. |
| `/etmc config` | Write a player config to join-config.toml in the plugin folder. |
| `/etmc reload` | Reload config.yml and reconnect. |
