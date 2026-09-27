# Region builds (swarm slice: partitioner + `buildRegion`)

Split one schematic across N bots that all build it at the same origin. No networking here; the swarm layer
(later slice) only has to hand each bot `(schematic file, origin, regionIndex, plan settings)`. Every bot computes
the same plan locally, since partitioning is deterministic. `#swarm build` (below) is that swarm layer.

```java
ISchematic schem = ...;                                        // same file on every bot, un-rotated
PartitionPlan plan = SchematicCells.partition(schem, botCount); // uses the buildPartition* settings
Map<Block, Integer> mats = SchematicCells.materials(schem, plan, myIndex);
baritone.getBuilderProcess().buildRegion("house", schem, origin, myIndex, plan);
// or only one part: buildRegion(name, schem, origin, myIndex, plan, RegionPart.INTERIOR / SEAM)
```

Code: `src/api/java/baritone/api/schematic/partition/` (pure-Java partitioner plus `SchematicCells` Minecraft adapter),
`IBuilderProcess.buildRegion`, and `BuilderProcess` (foreign-region guard). Tests: `SchematicPartitionerTest`.

## How it works
- `buildRegion` = `build(name, MaskSchematic.create(schematic, plan.regionMask(i, part)), origin)`. The builder skips
  every cell whose `inSchematic` is false (`fullRecalc`, `recalcNearby`, `placeAt`, `BuilderCalculationContext`),
  so a bot only places or clears cells of its own region. Blocks that are already correct are skipped, so re-running or
  re-assigning a region is safe.
- Regions are boxes that tile the **whole volume**, air included, so air cells that need clearing also have exactly one
  owner. Cuts are whole slices, placed so each region gets about the same number of **non-air** cells (within one
  slice). An all-air schematic is split by volume. More bots than slices leaves some regions empty (they finish at once).
- `buildSchematicRotation/Mirror` and `buildSubstitutes` are applied on top of the masked schematic, so plans are always
  made from the un-rotated schematic and every bot must use the same rotation/mirror.

## Settings
| Setting | Default | Meaning |
|---|---|---|
| `buildPartitionStrategy` | `strips` | `strips` (full-height strips), `grid` (full-height columns x rows, any N), `layers` (Y bands, bottom-up). |
| `buildPartitionAxis` | `auto` | Axis `strips` cuts / `grid` cuts into columns first: `auto` (longer of X/Z, X on a tie), `x`, `z`. |
| `buildPartitionGridColumns` | `0` | Grid columns; 0 = from the footprint's aspect ratio (capped at N). |
| `buildPartitionSeamWidth` | `1` | Seam band thickness per cut; 0 = no seams. |
| `buildRegionProtectForeign` | `true` | While building a region, pathing may not break or place inside other regions of the schematic. |

## Seams
Each internal cut at coordinate `c` has a band `[c - ceil(w/2), c + floor(w/2))`. Seam cells are **not** re-assigned:
each still belongs to the region whose box contains it (so the lower-index region keeps the extra layer when `w` is odd;
with `w = 1` the seam is the last layer of the lower-index region). Seams only split a region into `INTERIOR` and `SEAM`
so a coordinator can build all interiors first (neighbours never place along the same face at once) and seams after.
Building `WHOLE` ignores the split.

## Foreign-region protection
With `buildRegionProtectForeign`, `BuilderCalculationContext.isPossiblyProtected` is true for positions inside the
schematic's footprint but outside this bot's region (same origin, mirror and rotation as the build). Placing and breaking
there costs infinity, so pathing never digs through or scaffolds inside a neighbour's region. Walking through its air is
still allowed, and blocks outside the schematic are unaffected. It is reset by any plain `build`, by `onLostControl`, and
saved/restored with the AltoClef state stack. It does not touch the global `AltoClefSettings` avoiders.

## Limits (read before a live test)
- **Layers must be bottom-up.** `PartitionPlan.isOrdered()` is true for `layers`: band `i` sits on band `i-1`. Starting
  them together means floating blocks and falling sand/gravel. Nothing here enforces the barrier; the coordinator must.
- **Strips/grid are independent for gravity.** Regions span the full height, so the block below any cell is in the same
  region. Sand, gravel and concrete powder are supported by the region's own lower blocks.
- **Floating blocks and overhangs at a region edge.** Baritone needs an adjacent solid face to place against. If a block's
  only neighbour is in the next region (a bridge, overhang, torch or ladder on a neighbour's wall), the bot waits until
  that neighbour exists, or scaffolds from its own side. With protection on it may not scaffold inside a foreign region,
  so such a cell can stay unbuilt until the neighbour finishes. `SEAM`-last ordering or a final `WHOLE` pass by one bot
  after all regions finish picks these up.
- Throwaway scaffolding placed in the bot's own region air is cleared by the builder as usual (those cells want air).
- Protection only covers break/place during this bot's pathing; it cannot stop another bot, or a player, from changing
  blocks.

## Over the swarm link (`#swarm build`)
The group's lead (roster `lead=`) runs `#swarm build <group> <file> [x y z]` (origin defaults to its feet). The file must
be a plain name in every bot's `schematics` folder. Each member, in roster order, gets region `i` of `n` with the lead's
`buildPartition*` values carried in the order, so members' own settings do not matter. The lead builds its own region.
- Orders (`BUILD`), status (`BSTAT`: building / done / failed + reason) and `BSTOP` are sealed swarm messages. Members
  only accept orders and stops from the roster lead, as authenticated by the envelope.
- `layers` is enforced: band `i + 1` is sent only when band `i` reports done. `strips`/`grid` start together.
- An order with no answer after 60 s is sent once more. `#swarm status` shows each region's state; `#swarm stop` stops
  the job everywhere. Code: `baritone.swarm.SwarmBuild`, tests: `SwarmBuildTest`.
