# ForceRun Fortress

Minecraft Java 26.2 / Fabric mod.

Adds exactly one command:

`/forcerun fortress`

The command is restricted to permission level 2 and only works in the Nether.

Unlike `/place structure minecraft:fortress`, this implementation calls vanilla's procedural `Structure.generate(...)` to create the real `minecraft:fortress` `StructureStart` and its real `StructurePiece`s, places those pieces, then stores that same `StructureStart` in the affected chunks and adds structure references.

Build with Java 25:

```text
./gradlew build
```

Output:

`build/libs/forcerun-fortress-1.0.0.jar`
