# Ostinato (1.16.1)

Baritone fork for **Minecraft 1.16.1** / Fabric, carrying AltoClef hooks (`AltoClefSettings`, BuilderProcess stack APIs, inventory/tool/pathing hooks) from MiranCZ `baritone_altoclef` on cabaletta **1.16.5** sources, retargeted for TenorClef `1.16.1`.

## Critical 1.16.1 fixes

1. **`MixinClientPlayNetHandler.postHandleMultiBlockChange`** — no-op. Upstream 1.16.5 calls `SMultiBlockChangePacket.func_244310_a` (yarn `method_30621` / `visitUpdates`), which does not exist on 1.16.1 (`NoSuchMethodError`).
2. **`BlockOptionalMeta.drops`** — catch loot generation failures (including `minecraft:origin` loot table issues without a full server world) so BuilderProcess does not crash.

## Movement features

On top of the AltoClef hooks, this branch carries Ostinato's movement work:

- **Kinematic travel** (`#set kinematicTravel true`, off by default, experimental): plain walking
  stretches of a path (traverse, diagonal, 1-block ascend, drops up to 3 blocks) are driven by a
  per-tick physics look-ahead. `PlayerSim` is a copy of vanilla 1.16 player movement; each tick the
  controller simulates several yaw and jump choices and presses the keys of the one that gets
  furthest along the path while staying on it. Breaking, placing, water, ladders and parkour stay
  with Baritone.
- **Pitfall avoidance** (`pitfallAvoidance`, on by default): never stand on sand, gravel or concrete
  powder resting on a block without collision (air, an open fence gate, a sign...).
- **Water and air**: swimming, surface travel, and air management that uses bubble and magma columns.
- **Boats and elytra**: boat travel, including handling boats occupied by mobs, plus elytra gliding
  with rocket-free descent.
- **Sprint-jumping** on land.

Measure changes with TenorClef's `@pathbench travel [baritone|tungsten|kinematic] [reps]`. The latest run
(16 goals × 3 reps) had Baritone reach 46/48 goals at an average of 438 ticks, and kinematic reach 42/48
at an average of 431. Averages only cover the goals each mover reached.

## Artifact

Built / shipped as:

`dist/baritone-unoptimized-fabric-ostinato-1.16.1.jar`

TenorClef `:1.16.1` prefers this jar from `../Ostinato/dist` (see TenorClef `docs/OSTINATO_WIRING.md`).

## Build (from this branch)

Requires the legacy Baritone Fabric toolchain (Loom 0.7 / Java 8) used by cabaletta 1.16.5:

```bat
cd Ostinato-1.16.1
set JAVA_HOME=<JDK8>
gradlew.bat build -Pbaritone.fabric_build
```

Then copy `dist/baritone-unoptimized-fabric-*.jar` to `../Ostinato/dist/baritone-unoptimized-fabric-ostinato-1.16.1.jar`.

## Relationship to main

Ostinato `main` / `1.21.11` is modern cabaletta Unimined (MC 1.21.11). This `1.16.1` branch is a parallel lineage for legacy TenorClef — same AltoClef API intent, different Minecraft/mappings toolchain.
