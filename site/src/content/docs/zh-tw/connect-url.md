---
# Translated from src/content/docs/en/connect-url.md by scripts/translate.ts. Edit the English, then run `bun run translate`.
title: 設定檔 URL
description: 讓玩家透過你架設的設定檔加入。
---

伺服器管理員也可以不分享連結，改為架設一份設定檔。這很適合已經在 EasyTier 網路上的伺服器，因為伺服器本身不需要執行 etmc。

## 給玩家

如果連結以 `etmc://v1/config/` 開頭，就當作一般連結使用：貼到**新增伺服器**或**直接連線**。每次加入時，etmc 都會下載這份設定檔。

如果你手上是一般的 URL，請在 etmc 選單中選擇**使用連結加入**，點選**透過設定檔 URL 連線**並貼上，或執行 `/etmc connect <url>`。

## 給伺服器管理員

1. 用[產生器](/generator)建立檔案，或自己手寫。
2. 把它架設在公開的 HTTPS 網址上。
3. 在 URL 前面加上 `etmc://v1/config/`，分享這個連結，例如 `etmc://v1/config/https://example.com/etmc.toml`。

你變更檔案後，儲存了連結的玩家會在下次加入時取得更新，所以不必再發送新連結。

這類連結只會透過 HTTPS 載入，而且不會從私有或本地位址載入。如果檔案在你自己的網路上，或只能透過一般 HTTP 提供，請改為分享單純的 URL。

這個檔案就是一般的 EasyTier 設定檔，再加上一個指定伺服器的 `[etmc]` 區段：

```toml
dhcp = true

[network_identity]
network_name = "my-smp"
network_secret = "use-a-random-secret"

[[peer]]
uri = "tcp://relay.example.com:11010"

# Minecraft 伺服器在網路中的位址
[etmc]
server = "10.144.144.1:25565"
label = "My SMP"
# 僅限 etmc 伺服器：讓玩家確認連上的是你的伺服器
host_key = "from-etmc-status"
```

如果省略 `server`，玩家就得使用單純的 URL 並自行輸入位址。`etmc://v1/config/` 連結必須有 `server` 和至少一個中繼，而且只會從檔案讀取網路名稱、金鑰、中繼、伺服器、標籤和主機金鑰。這個 URL 也可以提供 `etmc://` 連結，而不是設定檔。

在執行 etmc 的 Paper 伺服器上，`/etmc config` 會寫出這個檔案並填入 `host_key`。其他伺服器請省略 `host_key`，因為玩家無法連上無法證明自己持有金鑰的伺服器。少了它，etmc 就無法確認玩家連上的是誰。

<div class="warn"><strong>你的金鑰不受保護。</strong>它在這個檔案中是明文，任何拿到 URL 的人都能讀取並加入。請保持隨機，切勿使用你在其他地方用過的密碼。</div>
