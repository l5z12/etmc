---
# Translated from src/content/docs/en/connect-url.md by scripts/translate.ts. Edit the English, then run `bun run translate`.
title: 配置 URL
description: 让玩家通过你托管的配置文件加入。
---

服务器管理员可以不分享链接，而是托管一个配置文件。这适合已经接入 EasyTier 网络的服务器，因为服务器不需要运行 etmc。

## 面向玩家

如果链接以 `etmc://v1/config/` 开头，就像使用其他链接一样使用它：将其粘贴到**添加服务器**或**直接连接**中。你每次加入时，etmc 都会下载该配置。

如果你拿到的是普通 URL，请在 etmc 菜单中选择**使用链接加入**，点击**通过配置 URL 连接**并粘贴它，或运行 `/etmc connect <url>`。

## 面向服务器管理员

1. 用[生成器](/generator)创建该文件，或手动编写。
2. 将其托管在公开的 HTTPS URL 上。
3. 在 URL 前面加上 `etmc://v1/config/`，然后分享该链接，例如 `etmc://v1/config/https://example.com/etmc.toml`。

当你修改文件后，保存了该链接的玩家会在下次加入时看到更改，因此你无需发送新链接。

这些链接只能通过 HTTPS 加载，不能从私有地址或本地地址加载。如果你的文件位于你自己的网络中，或使用普通 HTTP，请改为分享普通 URL。

该文件是一个普通的 EasyTier 配置，外加一个指定服务器的 `[etmc]` 段：

```toml
dhcp = true

[network_identity]
network_name = "my-smp"
network_secret = "use-a-random-secret"

[[peer]]
uri = "tcp://relay.example.com:11010"

# Minecraft 服务器在网络中的地址
[etmc]
server = "10.144.144.1:25565"
label = "My SMP"
# 仅用于 etmc 服务器：让玩家确认他们连到的是你的服务器
host_key = "from-etmc-status"
```

如果你省略 `server`，玩家就需要使用普通 URL 并自行输入地址。`etmc://v1/config/` 链接需要 `server` 和至少一个中继，并且只会从文件中读取网络名称、密钥、中继、服务器、标签和主机密钥。该 URL 也可以返回 `etmc://` 链接，而不是配置文件。

在运行 etmc 的 Paper 服务器上，`/etmc config` 会写入此文件，并填好其中的 `host_key`。对于其他任何服务器，请省略 `host_key`，因为玩家无法连接到无法证明自己持有密钥的服务器。没有主机密钥，etmc 就无法确认玩家连到的是谁。

<div class="warn"><strong>你的密钥不受保护。</strong>它在此文件中以明文保存，任何拿到该 URL 的人都能读取它并加入。请保持其随机性，并且切勿使用你在其他任何地方用过的密码。</div>
