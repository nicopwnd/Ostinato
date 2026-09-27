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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a schematic into N disjoint regions, one per bot, for parallel builds at a shared origin.
 * <p>
 * Pure Java: it only sees a {@link CellSource}, so it runs without Minecraft. Use
 * {@link SchematicCells#partition(baritone.api.schematic.ISchematic, int)} for a real schematic.
 * <p>
 * Regions are boxes that tile the whole volume (air included), so the union covers every non-air cell exactly once.
 * Cuts are whole slices, placed where the running non-air count is closest to the ideal share (ties go to the
 * middle of the tied run, then the lower coordinate). Each region is therefore within one slice's worth of
 * non-air cells of its share. A schematic with no non-air cells is split by volume instead.
 * More bots than slices simply leaves some regions empty.
 */
public final class SchematicPartitioner {

    private SchematicPartitioner() {}

    /** Strips along the longest horizontal axis, seam width 1: the {@code Settings} defaults. */
    public static PartitionPlan partition(int widthX, int heightY, int lengthZ, CellSource cells, int regions) {
        return partition(widthX, heightY, lengthZ, cells, regions, PartitionStrategy.STRIPS, 1);
    }

    public static PartitionPlan partition(int widthX, int heightY, int lengthZ, CellSource cells, int regions,
                                          PartitionStrategy strategy, int seamWidth) {
        return partition(widthX, heightY, lengthZ, cells, regions, strategy, seamWidth, PartitionAxis.AUTO, 0);
    }

    /**
     * @param widthX      schematic size along X
     * @param heightY     schematic size along Y
     * @param lengthZ     schematic size along Z
     * @param cells       occupancy of the schematic
     * @param regions     number of regions (bots), at least 1
     * @param strategy    how to cut
     * @param seamWidth   seam band width in cells, 0 for none (see {@link PartitionPlan})
     * @param axis        horizontal axis for strips / the first grid cut; {@link PartitionAxis#AUTO} = longer one
     * @param gridColumns grid columns along that axis, 0 = pick from the footprint's aspect ratio
     */
    public static PartitionPlan partition(int widthX, int heightY, int lengthZ, CellSource cells, int regions,
                                          PartitionStrategy strategy, int seamWidth, PartitionAxis axis, int gridColumns) {
        if (widthX < 0 || heightY < 0 || lengthZ < 0) {
            throw new IllegalArgumentException("negative schematic size");
        }
        if (regions < 1) {
            throw new IllegalArgumentException("need at least one region, got " + regions);
        }
        if (seamWidth < 0) {
            throw new IllegalArgumentException("seamWidth must be >= 0, got " + seamWidth);
        }
        if (gridColumns < 0) {
            throw new IllegalArgumentException("gridColumns must be >= 0 (0 = auto), got " + gridColumns);
        }
        if (strategy == null || axis == null || cells == null) {
            throw new IllegalArgumentException("null strategy, axis or cells");
        }

        // one pass over the volume: non-air count per (x,z) column and per y layer
        long[] column = new long[widthX * lengthZ];
        long[] layer = new long[heightY];
        for (int y = 0; y < heightY; y++) {
            for (int z = 0; z < lengthZ; z++) {
                for (int x = 0; x < widthX; x++) {
                    if (cells.occupied(x, y, z)) {
                        column[x * lengthZ + z]++;
                        layer[y]++;
                    }
                }
            }
        }
        boolean cutX = axis == PartitionAxis.X || (axis == PartitionAxis.AUTO && widthX >= lengthZ);

        List<BuildRegion> out = new ArrayList<>(regions);
        switch (strategy) {
            case LAYERS: {
                int[] b = cut(layer, equalShares(regions));
                for (int i = 0; i < regions; i++) {
                    out.add(new BuildRegion(i, 0, b[i], 0, widthX, b[i + 1], lengthZ, sum(layer, b[i], b[i + 1])));
                }
                break;
            }
            case STRIPS: {
                long[] w = cutX ? sumOverZ(column, widthX, lengthZ, 0, lengthZ) : sumOverX(column, widthX, lengthZ, 0, widthX);
                int[] b = cut(w, equalShares(regions));
                for (int i = 0; i < regions; i++) {
                    long n = sum(w, b[i], b[i + 1]);
                    out.add(cutX
                            ? new BuildRegion(i, b[i], 0, 0, b[i + 1], heightY, lengthZ, n)
                            : new BuildRegion(i, 0, 0, b[i], widthX, heightY, b[i + 1], n));
                }
                break;
            }
            case GRID: {
                int primary = cutX ? widthX : lengthZ;
                int secondary = cutX ? lengthZ : widthX;
                int cols = gridColumns > 0 ? Math.min(gridColumns, regions) : autoColumns(regions, primary, secondary);
                int[] rows = equalShares(regions, cols);
                long[] w = cutX ? sumOverZ(column, widthX, lengthZ, 0, lengthZ) : sumOverX(column, widthX, lengthZ, 0, widthX);
                int[] cb = cut(w, rows);
                int index = 0;
                for (int c = 0; c < cols; c++) {
                    long[] v = cutX ? sumOverX(column, widthX, lengthZ, cb[c], cb[c + 1]) : sumOverZ(column, widthX, lengthZ, cb[c], cb[c + 1]);
                    int[] rb = cut(v, equalShares(rows[c]));
                    for (int r = 0; r < rows[c]; r++) {
                        long n = sum(v, rb[r], rb[r + 1]);
                        out.add(cutX
                                ? new BuildRegion(index, cb[c], 0, rb[r], cb[c + 1], heightY, rb[r + 1], n)
                                : new BuildRegion(index, rb[r], 0, cb[c], rb[r + 1], heightY, cb[c + 1], n));
                        index++;
                    }
                }
                break;
            }
            default:
                throw new IllegalArgumentException("unsupported strategy " + strategy);
        }
        return new PartitionPlan(widthX, heightY, lengthZ, strategy, seamWidth, out);
    }

    /** Same as {@code materials(plan, region, RegionPart.WHOLE, blocks)}. */
    public static <K> Map<K, Integer> materials(PartitionPlan plan, int region, CellFunction<K> blocks) {
        return materials(plan, region, RegionPart.WHOLE, blocks);
    }

    /**
     * Material summary of one region: material to number of cells, in first-seen (y, z, x) scan order, so it is
     * deterministic. Summed over all regions it equals the whole schematic's totals.
     *
     * @param blocks material at a cell, {@code null} for air / don't-care cells (not counted)
     */
    public static <K> Map<K, Integer> materials(PartitionPlan plan, int region, RegionPart part, CellFunction<K> blocks) {
        BuildRegion r = plan.region(region);
        Map<K, Integer> counts = new LinkedHashMap<>();
        if (r.isEmpty()) {
            return counts;
        }
        baritone.api.schematic.mask.StaticMask mask = plan.regionMask(region, part);
        for (int y = r.minY(); y < r.maxY(); y++) {
            for (int z = r.minZ(); z < r.maxZ(); z++) {
                for (int x = r.minX(); x < r.maxX(); x++) {
                    if (part != RegionPart.WHOLE && !mask.partOfMask(x, y, z)) {
                        continue;
                    }
                    K k = blocks.at(x, y, z);
                    if (k != null) {
                        counts.merge(k, 1, Integer::sum);
                    }
                }
            }
        }
        return counts;
    }

    /** Columns for an auto grid: about square cells, never more columns than bots or than slices. */
    static int autoColumns(int regions, int primary, int secondary) {
        if (primary <= 0 || secondary <= 0) {
            return 1;
        }
        int cols = (int) Math.round(Math.sqrt(regions * (double) primary / secondary));
        return Math.max(1, Math.min(cols, Math.min(regions, primary)));
    }

    private static int[] equalShares(int parts) {
        int[] s = new int[parts];
        for (int i = 0; i < parts; i++) {
            s[i] = 1;
        }
        return s;
    }

    /** {@code total} split into {@code parts} near-equal integers, the remainder going to the first ones. */
    private static int[] equalShares(int total, int parts) {
        int[] s = new int[parts];
        for (int i = 0; i < parts; i++) {
            s[i] = total / parts + (i < total % parts ? 1 : 0);
        }
        return s;
    }

    /**
     * Boundaries {@code b[0]=0 <= b[1] <= ... <= b[k]=weights.length} for {@code k = shares.length} parts, where part
     * {@code i} = slices {@code [b[i], b[i+1])} should hold {@code shares[i] / sum(shares)} of the total weight.
     */
    static int[] cut(long[] weights, int[] shares) {
        int len = weights.length;
        int parts = shares.length;
        long total = 0;
        for (long w : weights) {
            total += w;
        }
        long[] prefix = new long[len + 1];
        for (int i = 0; i < len; i++) {
            prefix[i + 1] = prefix[i] + (total == 0 ? 1 : weights[i]); // nothing to place: split by volume
        }
        long whole = prefix[len];
        long shareSum = 0;
        for (int s : shares) {
            shareSum += s;
        }
        int[] b = new int[parts + 1];
        b[parts] = len;
        long acc = 0;
        for (int k = 1; k < parts; k++) {
            acc += shares[k - 1];
            long target = whole * acc; // compare prefix * shareSum against whole * acc, exact in integers
            // first position with the smallest error; the error is V-shaped in p but can have flat runs (air slices)
            int p = b[k - 1];
            long best = Math.abs(prefix[p] * shareSum - target);
            for (int i = p + 1; i <= len; i++) {
                long e = Math.abs(prefix[i] * shareSum - target);
                if (e < best) {
                    best = e;
                    p = i;
                }
            }
            int q = p;
            while (q + 1 <= len && Math.abs(prefix[q + 1] * shareSum - target) == best) {
                q++;
            }
            b[k] = (p + q) >>> 1;
        }
        return b;
    }

    private static long sum(long[] w, int from, int to) {
        long s = 0;
        for (int i = from; i < to; i++) {
            s += w[i];
        }
        return s;
    }

    /** Per-x totals of {@code column} over z in [z0, z1). */
    private static long[] sumOverZ(long[] column, int widthX, int lengthZ, int z0, int z1) {
        long[] out = new long[widthX];
        for (int x = 0; x < widthX; x++) {
            for (int z = z0; z < z1; z++) {
                out[x] += column[x * lengthZ + z];
            }
        }
        return out;
    }

    /** Per-z totals of {@code column} over x in [x0, x1). */
    private static long[] sumOverX(long[] column, int widthX, int lengthZ, int x0, int x1) {
        long[] out = new long[lengthZ];
        for (int x = x0; x < x1; x++) {
            for (int z = 0; z < lengthZ; z++) {
                out[z] += column[x * lengthZ + z];
            }
        }
        return out;
    }
}
