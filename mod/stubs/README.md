# Compile-only API stubs

Copies of the few interfaces etmc implements from optional mods, so the code compiles without
depending on a specific build of those mods on every Minecraft version. They are compiled into a
separate source set that only `main`'s compile classpath sees: nothing here is in the jar, and at
runtime the real mod supplies the classes (or etmc's entrypoint is never loaded, when it's absent).

Each stub must keep the exact signatures of the real interface (checked against the published jars),
or the JVM won't link etmc's implementation to it.

- `modmenu/yarn`: Mod Menu's API in yarn names, for the Fabric nodes 1.14.4–1.21.11. Mod Menu up to
  2.0.x (MC ≤1.17) has `io.github.prospector.modmenu.api`; 1.16.x and later have
  `com.terraformersmc.modmenu.api`.
- `modmenu/official`: the same API in official names, for the unobfuscated 26.x node.
