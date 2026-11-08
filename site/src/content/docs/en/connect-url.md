---
title: Config URLs
description: Let players join from a config file you host.
---

Instead of sharing a link, a server admin can host a config file. It suits a server that's already on an EasyTier
network, because the server doesn't need to run etmc.

## For players

If the link starts with `etmc://v1/config/`, use it like any other link: paste it into **Add Server** or
**Direct Connect**. etmc downloads the config each time you join.

If you have a plain URL, choose **Join with a link** in the etmc menu, click **Connect via config URL** and paste it,
or run `/etmc connect <url>`.

## For server admins

1. Create the file with the [generator](/generator), or write it by hand.
2. Host it at a public HTTPS URL.
3. Put `etmc://v1/config/` in front of the URL and share that link, for example
   `etmc://v1/config/https://example.com/etmc.toml`.

When you change the file, players who saved the link get the change the next time they join, so you don't need to
send a new link.

These links only load over HTTPS, and not from a private or local address. For a file on your own network or on
plain HTTP, share the plain URL instead.

The file is a normal EasyTier config, plus an `[etmc]` section that names the server:

```toml
dhcp = true

[network_identity]
network_name = "my-smp"
network_secret = "use-a-random-secret"

[[peer]]
uri = "tcp://relay.example.com:11010"

# The Minecraft server's address on the network
[etmc]
server = "10.144.144.1:25565"
label = "My SMP"
# Only on etmc servers: lets players confirm they reached yours
host_key = "from-etmc-status"
```

If you leave out `server`, players need the plain URL and enter the address themselves. An `etmc://v1/config/` link
needs `server` and at least one relay, and only reads the network, secret, relays, server, label and host key from
the file. The URL can also serve an `etmc://` link instead of a config.

On a Paper server running etmc, `/etmc config` writes this file with its `host_key` filled in. Leave `host_key` out
for any other server, because players can't connect to a server that can't prove it holds the key. Without one, etmc
can't check who players reached.

<div class="warn"><strong>Your secret isn't protected.</strong> It's plain text in this file, and anyone with the URL
can read it and join. Keep it random, and never use a password you use anywhere else.</div>
