---
title: Hosting
description: Share your singleplayer world or a Paper server.
---

## Host a singleplayer world

1. Open your world, then press <kbd>G</kbd> or click **etmc** in the pause menu, and choose **Host this world**.
2. If you haven't added a relay yet, paste one in. **Find a relay** opens a list of public ones.
3. Click **Start hosting**. etmc copies your invite link for you.
4. Send the link to your friends.

<div class="warn"><strong>Your secret isn't protected.</strong> It's saved as plain text in <code>config/etmc.json</code>,
and anyone with your link can read it and join. Keep it random, and never use a password you use anywhere else.</div>

Keep the world open while friends are playing. Leaving the world stops hosting. To copy the link again, open
**Session status** and click **Copy invite link**, or run `/etmc invite`.

etmc picks a random network name and secret. To see or change them, click **More options**.

## Let offline accounts join

Minecraft checks each player's account. To let offline accounts join your world, click **More options** and turn on
**Offline accounts** before you click **Start hosting**. Stopping hosting turns the check back on. On a Paper server,
set `online-mode=false` in `server.properties` instead.

<div class="warn"><strong>Names aren't checked with this on.</strong> Anyone with your link can join under any name.
Friends with real accounts start with a new inventory, and get their old one back when it's off.</div>

## Show who's inviting

Under **More options**, turn on **Sign as me** before you click **Start hosting**, and your link shows
"&lt;your name&gt; invites you to join their world". Players can confirm that with Mojang, so nobody else can use
your name on a link. It's off unless you turn it on.

etmc signs each player's connection with the key Minecraft uses for your chat, and players see your account. It
needs a real Minecraft account, and a recent version of the game.

## Host a Paper server

1. Download the etmc Paper jar from [Releases](https://github.com/L5Z12/etmc/releases) and put it in your `plugins`
   folder.
2. Create a config with the [generator](/generator), which suggests a random secret, and save it as
   `plugins/etmc/config.yml`.
3. Restart the server, or run `/etmc reload`.
4. Copy the `etmc://` link from the server log, or run `/etmc link`.
5. Share the link with your players.

<div class="warn"><strong>Your secret isn't protected.</strong> It's plain text in <code>config.yml</code>, and anyone
with your link can read it and join. If you leave the default <code>change-me</code>, etmc replaces it with a random
one. Keep it random, and never use a password you use anywhere else.</div>

Running a public server that anyone can join? Leave the secret empty.

## Keep your host key

Everyone with your link can read the secret, so the secret can't prove who the host is. Your link also holds a host
key, which lets players check they've reached you and not someone posing as you. etmc makes the key the first time
you host, and saves it in `config/etmc/host-key`, or `plugins/etmc/host-key` on a Paper server.

Your links keep working as long as you keep that file, so back it up. If you lose it, or someone else gets a copy,
delete it, host again and share a new link.

<div class="warn"><strong>Never share the host-key file.</strong> Anyone who has it can pose as you. Leave it out of
modpacks and shared config folders.</div>

If someone takes your address on the network, your players will refuse to connect to them, but they may not be able
to reach you either. Move to a new network name and secret, and share a new link.

## Stop anyone taking your server's address

If players can reach your Paper server, set `public-address` in `config.yml`, for example
`tcp://play.example.com:11010`, and open that port. Players then connect straight to your server, so nobody on the
network can take its address from them, and you don't need a relay. Share the new link afterwards.

If you share a config URL instead of a link, you can also move to a new network without players needing a new link.
