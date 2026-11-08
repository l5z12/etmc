---
title: Joining
description: Join a world or server with an invite link.
---

## Join with a link

1. In Minecraft, open **Multiplayer**, then **Add Server** or **Direct Connect**.
2. Paste the `etmc://` link into the address field.
3. Click **Join Server**.

If the host chose to sign with their Minecraft account, the link shows who invited you. etmc checks that name with
Mojang when you connect, and won't join if the host can't prove it. If Mojang can't be reached, etmc asks you first.
**Session status** shows when Mojang has confirmed them.

etmc tries for a direct connection first. If that's taking too long, click **Join now anyway** to skip the wait.
This can fail if the host isn't reachable yet.

If etmc gives up waiting for the host, or finds their world closed, it says why. Once they're hosting again, click
**Try again**.

## Join from the etmc menu

1. Press <kbd>G</kbd> and choose **Join with a link**.
2. If you've copied the link, it's already filled in. If not, paste it in.
3. Click **Join**.

From the title screen, etmc connects right away. From inside a world, it adds the server to your **Multiplayer** list
so you can join after leaving. You can also run `/etmc join <link>`.

## If etmc can't confirm the host

Your link names the host's key, and etmc connects only if the host can prove it's theirs. If it can't, etmc stops and
tells you. Someone on the network may be posing as the host, or the host may have a new key. Ask them for a new link,
somewhere you know it's really them.

Older links don't have a key, so etmc can't check who you reach. It asks before joining with one: get a new link from
the host if you can. If you choose **Join anyway**, **Session status** shows that the host wasn't checked.

## Check your connection

Run `/etmc status` to see how many players are connected, and how many of them directly.

## Don't have a link?

If a server admin gave you a config URL, see [Config URLs](/docs/connect-url). If you know the network details, you
can build a link with the [generator](/generator).
