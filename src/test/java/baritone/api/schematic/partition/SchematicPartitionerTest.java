/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.schematic.partition;

import baritone.api.schematic.AbstractSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.MaskSchematic;
import baritone.api.schematic.RotatedSchematic;
import baritone.api.schematic.mask.StaticMask;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Rotation;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Pure-logic tests for the schematic partitioner, against synthetic schematics (int material ids, 0 = air).
 * No Minecraft runtime: BuilderProcess itself needs a client, so the region build is covered by testing the mask
 * it hands to {@link MaskSchematic} (the builder skips every cell where {@code inSchematic} is false).
 */
public class SchematicPartitionerTest {

    private static final PartitionStrategy[] STRATEGIES = PartitionStrategy.values();

    /** Synthetic schematic: material id per cell, 0 = air. */
    private static final class Synth {
        final int w, h, l;
        final int[] ids;

        Synth(int w, int h, int l) {
            this.w = w;
            this.h = h;
            this.l = l;
            this.ids = new int[w * h * l];
        }

        int get(int x, int y, int z) {
            return ids[(y * l + z) * w + x];
        }

        void set(int x, int y, int z, int id) {
            ids[(y * l + z) * w + x] = id;
        }

        CellSource cells() {
            return (x, y, z) -> get(x, y, z) != 0;
        }

        CellFunction<Integer> materials() {
            return (x, y, z) -> get(x, y, z) == 0 ? null : get(x, y, z);
        }

        long nonAir() {
            long n = 0;
            for (int id : ids) {
                if (id != 0) {
                    n++;
                }
            }
            return n;
        }

        Map<Integer, Integer> totals() {
            Map<Integer, Integer> m = new HashMap<>();
            for (int id : ids) {
                if (id != 0) {
                    m.merge(id, 1, Integer::sum);
                }
            }
            return m;
        }

        PartitionPlan plan(int n, PartitionStrategy s, int seam) {
            return SchematicPartitioner.partition(w, h, l, cells(), n, s, seam);
        }
    }

    /**
     * A "house": floor, hollow walls, a gabled roof, random furniture, an empty courtyard stretch along X
     * (whole air slices), and a floating block. Deterministic.
     */
    private static Synth house(int w, int h, int l, long seed) {
        Synth s = new Synth(w, h, l);
        Random r = new Random(seed);
        int courtyard0 = w / 3, courtyard1 = w / 3 + Math.max(1, w / 8);
        for (int x = 0; x < w; x++) {
            if (x >= courtyard0 && x < courtyard1) {
                continue; // air slices: cuts must cope with zero-weight runs
            }
            for (int z = 0; z < l; z++) {
                s.set(x, 0, z, 1); // floor
                for (int y = 1; y < h - 2; y++) {
                    boolean wall = x == 0 || x == w - 1 || z == 0 || z == l - 1 || x == courtyard0 - 1 || x == courtyard1;
                    if (wall) {
                        s.set(x, y, z, 2);
                    } else if (r.nextInt(10) == 0) {
                        s.set(x, y, z, 3 + r.nextInt(4)); // furniture, 4 kinds
                    }
                }
                int roofY = h - 2 + (Math.abs(z - l / 2) < l / 4 ? 1 : 0);
                s.set(x, roofY, z, 7);
            }
        }
        s.set(courtyard0, h - 1, 0, 8); // floating block over the courtyard
        return s;
    }

    private static Synth random(int w, int h, int l, double density, long seed) {
        Synth s = new Synth(w, h, l);
        Random r = new Random(seed);
        for (int i = 0; i < s.ids.length; i++) {
            s.ids[i] = r.nextDouble() < density ? 1 + r.nextInt(5) : 0;
        }
        return s;
    }

    // ---- coverage + disjointness ----

    private static void assertTiles(Synth s, PartitionPlan p, int n, String label) {
        assertEquals(label, n, p.regionCount());
        StaticMask[] whole = new StaticMask[n], interior = new StaticMask[n], seam = new StaticMask[n];
        long[] counted = new long[n];
        for (int i = 0; i < n; i++) {
            whole[i] = p.regionMask(i);
            interior[i] = p.regionMask(i, RegionPart.INTERIOR);
            seam[i] = p.regionMask(i, RegionPart.SEAM);
        }
        for (int y = 0; y < s.h; y++) {
            for (int z = 0; z < s.l; z++) {
                for (int x = 0; x < s.w; x++) {
                    int owners = 0, owner = -1;
                    for (int i = 0; i < n; i++) {
                        boolean in = whole[i].partOfMask(x, y, z);
                        boolean inI = interior[i].partOfMask(x, y, z);
                        boolean inS = seam[i].partOfMask(x, y, z);
                        assertEquals(label + " interior/seam split at " + x + "," + y + "," + z, in, inI || inS);
                        assertFalse(label + " interior and seam overlap", inI && inS);
                        if (in) {
                            owners++;
                            owner = i;
                        }
                    }
                    assertEquals(label + " owners of " + x + "," + y + "," + z, 1, owners);
                    assertEquals(label, owner, p.owner(x, y, z));
                    assertEquals(label, p.isSeam(x, y, z), seam[owner].partOfMask(x, y, z));
                    if (s.get(x, y, z) != 0) {
                        counted[owner]++;
                    }
                }
            }
        }
        long sum = 0;
        for (int i = 0; i < n; i++) {
            assertEquals(label + " nonAirCount of region " + i, counted[i], p.region(i).nonAirCount());
            sum += counted[i];
        }
        assertEquals(label + " every non-air cell covered once", s.nonAir(), sum);
        assertEquals(label, s.nonAir(), p.totalNonAir());
        // nothing outside the schematic
        for (int i = 0; i < n; i++) {
            assertFalse(whole[i].partOfMask(-1, 0, 0));
            assertFalse(whole[i].partOfMask(s.w, 0, 0));
            assertFalse(whole[i].partOfMask(0, s.h, 0));
            assertFalse(whole[i].partOfMask(0, 0, -1));
        }
        assertEquals(-1, p.owner(s.w, 0, 0));
    }

    @Test
    public void everyStrategyTilesTheSchematicForOneToEightBots() {
        Synth s = house(37, 9, 23, 42);
        for (PartitionStrategy strategy : STRATEGIES) {
            for (int n = 1; n <= 8; n++) {
                for (int seam = 0; seam <= 3; seam++) {
                    assertTiles(s, s.plan(n, strategy, seam), n, strategy + " n=" + n + " seam=" + seam);
                }
            }
        }
    }

    @Test
    public void explicitAxisAndGridColumnsStillTile() {
        Synth s = house(30, 7, 26, 7);
        for (PartitionAxis axis : PartitionAxis.values()) {
            for (int cols = 0; cols <= 9; cols++) {
                for (int n = 1; n <= 8; n++) {
                    PartitionPlan p = SchematicPartitioner.partition(s.w, s.h, s.l, s.cells(), n, PartitionStrategy.GRID, 1, axis, cols);
                    assertTiles(s, p, n, "grid axis=" + axis + " cols=" + cols + " n=" + n);
                }
                PartitionPlan strips = SchematicPartitioner.partition(s.w, s.h, s.l, s.cells(), 5, PartitionStrategy.STRIPS, 1, axis, cols);
                assertTiles(s, strips, 5, "strips axis=" + axis);
            }
        }
        // strips cut the requested axis: every region spans the other one fully
        PartitionPlan z = SchematicPartitioner.partition(s.w, s.h, s.l, s.cells(), 4, PartitionStrategy.STRIPS, 1, PartitionAxis.Z, 0);
        for (BuildRegion r : z.regions()) {
            assertEquals(0, r.minX());
            assertEquals(s.w, r.maxX());
            assertEquals(s.h, r.maxY());
        }
        PartitionPlan auto = s.plan(4, PartitionStrategy.STRIPS, 1); // 30 >= 26 -> X
        for (BuildRegion r : auto.regions()) {
            assertEquals(0, r.minZ());
            assertEquals(s.l, r.maxZ());
        }
    }

    @Test
    public void onlyLayersAreOrdered() {
        Synth s = house(20, 6, 20, 1);
        assertFalse(s.plan(3, PartitionStrategy.STRIPS, 1).isOrdered());
        assertFalse(s.plan(3, PartitionStrategy.GRID, 1).isOrdered());
        PartitionPlan layers = s.plan(3, PartitionStrategy.LAYERS, 1);
        assertTrue(layers.isOrdered());
        for (int i = 1; i < 3; i++) { // bottom-up: region i starts where i-1 ends
            assertEquals(layers.region(i - 1).maxY(), layers.region(i).minY());
        }
        assertEquals(0, layers.region(0).minY());
    }

    // ---- balance ----

    /** Provable bound: each cut lands within half a slice of its target, so a region is off by at most one slice. */
    @Test
    public void balanceWithinOneSliceOfTheIdealShare() {
        Synth s = house(41, 10, 29, 99);
        long[] perX = new long[s.w], perY = new long[s.h], perZ = new long[s.l];
        for (int y = 0; y < s.h; y++) {
            for (int z = 0; z < s.l; z++) {
                for (int x = 0; x < s.w; x++) {
                    if (s.get(x, y, z) != 0) {
                        perX[x]++;
                        perY[y]++;
                        perZ[z]++;
                    }
                }
            }
        }
        double total = s.nonAir();
        for (int n = 1; n <= 8; n++) {
            double ideal = total / n;
            assertMaxDeviation(s.plan(n, PartitionStrategy.STRIPS, 1), ideal, max(perX), "strips n=" + n);
            assertMaxDeviation(s.plan(n, PartitionStrategy.LAYERS, 1), ideal, max(perY), "layers n=" + n);
            assertMaxDeviation(s.plan(n, PartitionStrategy.GRID, 1), ideal, max(perX) + max(perZ), "grid n=" + n);
        }
    }

    /** Practical tolerance: on schematics with many slices every region is within 10% of the mean. */
    @Test
    public void balanceWithinTenPercentOnLargeSchematics() {
        Synth flat = random(128, 8, 128, 0.3, 5);
        Synth tall = random(16, 128, 16, 0.3, 6);
        for (int n = 1; n <= 8; n++) {
            assertWithinFraction(flat.plan(n, PartitionStrategy.STRIPS, 1), 0.10, "strips n=" + n);
            assertWithinFraction(flat.plan(n, PartitionStrategy.GRID, 1), 0.10, "grid n=" + n);
            assertWithinFraction(tall.plan(n, PartitionStrategy.LAYERS, 1), 0.10, "layers n=" + n);
        }
    }

    @Test
    public void balanceIsByNonAirCountNotVolume() {
        // all blocks in the first quarter along X: 2 strips must both cut inside that quarter
        Synth s = new Synth(40, 4, 10);
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 10; z++) {
                    s.set(x, y, z, 1);
                }
            }
        }
        PartitionPlan p = s.plan(2, PartitionStrategy.STRIPS, 1);
        assertEquals(200, p.region(0).nonAirCount());
        assertEquals(200, p.region(1).nonAirCount());
        assertEquals(5, p.region(0).maxX());
    }

    private static long max(long[] a) {
        long m = 0;
        for (long v : a) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static void assertMaxDeviation(PartitionPlan p, double ideal, long bound, String label) {
        for (BuildRegion r : p.regions()) {
            assertTrue(label + " " + r + " ideal=" + ideal + " bound=" + bound, Math.abs(r.nonAirCount() - ideal) <= bound);
        }
    }

    private static void assertWithinFraction(PartitionPlan p, double fraction, String label) {
        double ideal = p.totalNonAir() / (double) p.regionCount();
        for (BuildRegion r : p.regions()) {
            assertTrue(label + " " + r + " ideal=" + ideal, Math.abs(r.nonAirCount() - ideal) <= fraction * ideal);
        }
    }

    // ---- seams ----

    @Test
    public void seamsAreDeterministic() {
        Synth a = house(33, 8, 21, 3);
        Synth b = house(33, 8, 21, 3); // same content, different instance
        for (PartitionStrategy strategy : STRATEGIES) {
            for (int n = 1; n <= 8; n++) {
                for (int seam = 0; seam <= 3; seam++) {
                    PartitionPlan p = a.plan(n, strategy, seam);
                    PartitionPlan q = b.plan(n, strategy, seam);
                    assertEquals(p, q);
                    assertEquals(p.hashCode(), q.hashCode());
                    assertEquals(p.toString(), q.toString());
                    for (int y = 0; y < a.h; y++) {
                        for (int z = 0; z < a.l; z++) {
                            for (int x = 0; x < a.w; x++) {
                                assertEquals(p.isSeam(x, y, z), q.isSeam(x, y, z));
                            }
                        }
                    }
                    // seams never move region boundaries
                    assertEquals(a.plan(n, strategy, 0).regions(), p.regions());
                }
            }
        }
    }

    @Test
    public void seamBandStraddlesEachCutWithTheExtraLayerOnTheLowerIndexSide() {
        Synth s = random(60, 3, 5, 0.5, 11); // strips along X, regions ~15 thick
        for (int w = 0; w <= 4; w++) {
            PartitionPlan p = s.plan(4, PartitionStrategy.STRIPS, w);
            for (int x = 0; x < s.w; x++) {
                boolean expected = false;
                for (int k = 1; k < 4; k++) {
                    int c = p.region(k).minX();
                    assertEquals(p.region(k - 1).maxX(), c);
                    if (x >= c - (w + 1) / 2 && x < c + w / 2) {
                        expected = true;
                        // owner is simply the region whose box contains the cell
                        assertEquals(x < c ? k - 1 : k, p.owner(x, 1, 2));
                    }
                }
                for (int y = 0; y < s.h; y++) {
                    for (int z = 0; z < s.l; z++) {
                        assertEquals("w=" + w + " x=" + x, expected, p.isSeam(x, y, z));
                    }
                }
            }
            // w=1: the seam is exactly the last layer of the lower-index region
            if (w == 1) {
                for (int k = 0; k < 3; k++) {
                    int last = p.region(k).maxX() - 1;
                    assertTrue(p.isSeam(last, 0, 0));
                    assertEquals(k, p.owner(last, 0, 0));
                    assertFalse(p.isSeam(p.region(k + 1).minX(), 0, 0));
                }
            }
        }
        // the outside faces of the schematic are never seams
        PartitionPlan p = s.plan(4, PartitionStrategy.STRIPS, 3);
        assertFalse(p.isSeam(0, 0, 0));
        assertFalse(p.isSeam(s.w - 1, 0, 0));
    }

    @Test
    public void gridAndLayerSeamsFollowTheirOwnCuts() {
        Synth s = random(24, 12, 24, 0.4, 13);
        PartitionPlan grid = s.plan(4, PartitionStrategy.GRID, 1); // auto: 2 columns x 2 rows
        for (BuildRegion r : grid.regions()) {
            for (int z = r.minZ(); z < r.maxZ(); z++) {
                for (int x = r.minX(); x < r.maxX(); x++) {
                    boolean expected = (r.maxX() < s.w && x == r.maxX() - 1) || (r.maxZ() < s.l && z == r.maxZ() - 1);
                    assertEquals(r + " " + x + "," + z, expected, grid.isSeam(x, 5, z));
                }
            }
        }
        PartitionPlan layers = s.plan(3, PartitionStrategy.LAYERS, 1);
        for (int y = 0; y < s.h; y++) {
            BuildRegion r = layers.region(layers.owner(0, y, 0));
            assertEquals(r.maxY() < s.h && y == r.maxY() - 1, layers.isSeam(7, y, 7));
        }
    }

    // ---- materials ----

    @Test
    public void materialCountsSumToTheSchematicTotals() {
        Synth s = house(35, 9, 27, 21);
        Map<Integer, Integer> totals = s.totals();
        for (PartitionStrategy strategy : STRATEGIES) {
            for (int n = 1; n <= 8; n++) {
                PartitionPlan p = s.plan(n, strategy, 1);
                Map<Integer, Integer> sum = new HashMap<>();
                for (int i = 0; i < n; i++) {
                    Map<Integer, Integer> region = SchematicPartitioner.materials(p, i, s.materials());
                    long regionSum = 0;
                    for (Map.Entry<Integer, Integer> e : region.entrySet()) {
                        sum.merge(e.getKey(), e.getValue(), Integer::sum);
                        regionSum += e.getValue();
                    }
                    assertEquals(p.region(i).nonAirCount(), regionSum);
                    // interior + seam = whole
                    Map<Integer, Integer> parts = new HashMap<>(SchematicPartitioner.materials(p, i, RegionPart.INTERIOR, s.materials()));
                    SchematicPartitioner.materials(p, i, RegionPart.SEAM, s.materials()).forEach((k, v) -> parts.merge(k, v, Integer::sum));
                    assertEquals(new HashMap<>(region), parts);
                }
                assertEquals(strategy + " n=" + n, totals, sum);
            }
        }
    }

    // ---- edge cases ----

    @Test
    public void emptySchematicIsSplitByVolumeAndStillTiles() {
        Synth s = new Synth(16, 4, 9);
        for (PartitionStrategy strategy : STRATEGIES) {
            for (int n = 1; n <= 8; n++) {
                PartitionPlan p = s.plan(n, strategy, 1);
                assertTiles(s, p, n, "empty " + strategy + " n=" + n);
                assertEquals(0, p.totalNonAir());
                for (int i = 0; i < n; i++) {
                    assertTrue(SchematicPartitioner.materials(p, i, s.materials()).isEmpty());
                }
            }
        }
        // 16 wide, 4 strips of air: 4 blocks each
        for (BuildRegion r : s.plan(4, PartitionStrategy.STRIPS, 1).regions()) {
            assertEquals(4, r.maxX() - r.minX());
        }
        // zero-size schematic: every region empty, nothing breaks
        PartitionPlan zero = SchematicPartitioner.partition(0, 0, 0, (x, y, z) -> true, 5, PartitionStrategy.GRID, 1);
        assertEquals(5, zero.regionCount());
        for (BuildRegion r : zero.regions()) {
            assertTrue(r.isEmpty());
        }
    }

    @Test
    public void moreBotsThanCells() {
        Synth s = new Synth(6, 3, 5);
        s.set(0, 0, 0, 1);
        s.set(3, 1, 2, 2);
        s.set(5, 2, 4, 1);
        for (PartitionStrategy strategy : STRATEGIES) {
            for (int n : new int[]{3, 4, 8, 50}) {
                PartitionPlan p = s.plan(n, strategy, 1);
                assertTiles(s, p, n, "sparse " + strategy + " n=" + n);
                int busy = 0;
                for (BuildRegion r : p.regions()) {
                    assertTrue(strategy + " n=" + n + " " + r, r.nonAirCount() <= 1);
                    if (r.nonAirCount() > 0) {
                        busy++;
                    }
                }
                assertEquals(3, busy);
            }
        }
    }

    @Test
    public void rejectsBadArguments() {
        CellSource any = (x, y, z) -> true;
        expectIae(() -> SchematicPartitioner.partition(4, 4, 4, any, 0));
        expectIae(() -> SchematicPartitioner.partition(4, 4, 4, any, 2, PartitionStrategy.STRIPS, -1));
        expectIae(() -> SchematicPartitioner.partition(-1, 4, 4, any, 2));
        expectIae(() -> SchematicPartitioner.partition(4, 4, 4, any, 2, PartitionStrategy.GRID, 1, PartitionAxis.AUTO, -1));
        PartitionPlan p = SchematicPartitioner.partition(4, 4, 4, any, 2);
        expectIae(() -> p.regionMask(2));
        expectIae(() -> p.region(-1));
        expectIae(() -> PartitionStrategy.parse("balanced"));
        expectIae(() -> PartitionAxis.parse("y"));
        assertEquals(PartitionStrategy.GRID, PartitionStrategy.parse(" Grid "));
        assertEquals(PartitionStrategy.STRIPS, PartitionStrategy.parse("strips"));
        assertEquals(PartitionStrategy.LAYERS, PartitionStrategy.parse("LAYERS"));
        assertEquals(PartitionAxis.Z, PartitionAxis.parse("z"));
        assertTrue(p.fits(4, 4, 4));
        assertFalse(p.fits(4, 4, 5));
    }

    private static void expectIae(Runnable r) {
        try {
            r.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ---- what BuilderProcess consumes ----

    private static ISchematic box(int w, int h, int l) {
        return new AbstractSchematic(w, h, l) {
            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                return current;
            }
        };
    }

    /**
     * buildRegion = build(MaskSchematic.create(schematic, plan.regionMask(i))); BuilderProcess skips every cell where
     * inSchematic is false (fullRecalc, placeAt, BuilderCalculationContext). So the masked schematics of all regions
     * must claim each cell exactly once, and never claim a cell outside the schematic.
     */
    @Test
    public void maskedSchematicsClaimEachCellOnce() {
        Synth s = house(19, 6, 13, 17);
        ISchematic whole = box(s.w, s.h, s.l);
        for (PartitionStrategy strategy : STRATEGIES) {
            for (int n = 1; n <= 8; n++) {
                PartitionPlan p = s.plan(n, strategy, 1);
                MaskSchematic[] masked = new MaskSchematic[n];
                for (int i = 0; i < n; i++) {
                    masked[i] = MaskSchematic.create(whole, p.regionMask(i));
                }
                for (int y = -1; y <= s.h; y++) {
                    for (int z = -1; z <= s.l; z++) {
                        for (int x = -1; x <= s.w; x++) {
                            int claims = 0;
                            for (int i = 0; i < n; i++) {
                                if (masked[i].inSchematic(x, y, z, null)) {
                                    claims++;
                                    assertEquals(i, p.owner(x, y, z));
                                }
                            }
                            boolean inside = whole.inSchematic(x, y, z, null);
                            assertEquals(strategy + " n=" + n + " " + x + "," + y + "," + z, inside ? 1 : 0, claims);
                        }
                    }
                }
            }
        }
    }

    /**
     * buildSchematicRotation is applied on top of the masked schematic, and the foreign-region guard applies the same
     * rotation to the region masks. Under every rotation the rotated regions must still tile the rotated footprint.
     */
    @Test
    public void rotatedMasksStillTileTheRotatedFootprint() {
        Synth s = house(11, 4, 7, 23);
        ISchematic whole = box(s.w, s.h, s.l);
        for (PartitionStrategy strategy : STRATEGIES) {
            PartitionPlan p = s.plan(3, strategy, 1);
            for (Rotation rot : Rotation.values()) {
                ISchematic footprint = new RotatedSchematic(whole, rot);
                ISchematic[] owned = new ISchematic[3];
                for (int i = 0; i < 3; i++) {
                    owned[i] = new RotatedSchematic(MaskSchematic.create(whole, p.regionMask(i)), rot);
                }
                for (int y = 0; y < footprint.heightY(); y++) {
                    for (int z = 0; z < footprint.lengthZ(); z++) {
                        for (int x = 0; x < footprint.widthX(); x++) {
                            assertTrue(footprint.inSchematic(x, y, z, null));
                            int claims = 0;
                            for (int i = 0; i < 3; i++) {
                                if (owned[i].inSchematic(x, y, z, null)) {
                                    claims++;
                                }
                            }
                            assertEquals(strategy + " " + rot, 1, claims);
                        }
                    }
                }
            }
        }
    }
}
