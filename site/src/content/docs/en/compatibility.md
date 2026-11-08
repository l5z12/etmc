---
title: Mod compatibility
description: How etmc works alongside other mods.
---

etmc only changes how the connection travels. It doesn't touch Minecraft's packets, so to the game and your other
mods it's a normal server connection.

## ViaFabricPlus

etmc works with ViaFabricPlus, including its automatic version detection, with nothing to set up. etmc doesn't
require it.

To keep a version you've forced in ViaFabricPlus between sessions when you join from a config URL, set
`joinLocalPort` in `config/etmc.json` to a fixed port. ViaFabricPlus saves its settings per address, and etmc
otherwise picks a new port each time.
