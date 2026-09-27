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

import baritone.api.schematic.mask.StaticMask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The result of {@link SchematicPartitioner}: N disjoint {@link BuildRegion boxes} that together tile the whole
 * schematic volume, so every cell (and in particular every non-air cell) has exactly one owner.
 * <p>
 * <b>Seams.</b> Every internal cut between two regions has a seam band {@link #seamWidth()} cells thick. For a cut
 * at coordinate {@code c} (the high-side region starts at {@code c}) the band is
 * {@code [c - ceil(w/2), c + floor(w/2))}: the low-side region keeps {@code ceil(w/2)} layers of it and the
 * high-side region {@code floor(w/2)}. So with the default width 1 the seam is the last layer of the lower-index
 * region. Seam cells are never re-assigned: a seam cell still belongs to the region whose box contains it, so
 * seams add no gap and no overlap. They only split each region into {@link RegionPart#INTERIOR} and
 * {@link RegionPart#SEAM}, which lets a coordinator build interiors first (neighbours never place along the same
 * face at the same time) and seams afterwards. Width 0 disables seams.
 * <p>
 * <b>Order.</b> {@link PartitionStrategy#STRIPS} and {@link PartitionStrategy#GRID} regions span the full height,
 * so every block's support below it is in its own region and regions can be built in parallel in any order.
 * {@link PartitionStrategy#LAYERS} bands sit on each other and must be built bottom-up ({@link #isOrdered()}).
 * <p>
 * Plans are immutable, deterministic for the same input and compare with {@link #equals(Object)}.
 */
public final class PartitionPlan {

    private final int widthX, heightY, lengthZ;
    private final PartitionStrategy strategy;
    private final int seamWidth;
    private final List<BuildRegion> regions;
    private final long totalNonAir;

    public PartitionPlan(int widthX, int heightY, int lengthZ, PartitionStrategy strategy, int seamWidth, List<BuildRegion> regions) {
        if (widthX < 0 || heightY < 0 || lengthZ < 0) {
            throw new IllegalArgumentException("negative schematic size");
        }
        if (seamWidth < 0) {
            throw new IllegalArgumentException("seamWidth must be >= 0");
        }
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("a plan needs at least one region");
        }
        long total = 0;
        for (int i = 0; i < regions.size(); i++) {
            BuildRegion r = regions.get(i);
            if (r.index() != i) {
                throw new IllegalArgumentException("region " + i + " has index " + r.index());
            }
            if (r.minX() < 0 || r.minY() < 0 || r.minZ() < 0 || r.maxX() > widthX || r.maxY() > heightY || r.maxZ() > lengthZ) {
                throw new IllegalArgumentException("region " + r + " is outside the schematic");
            }
            total += r.nonAirCount();
        }
        this.widthX = widthX;
        this.heightY = heightY;
        this.lengthZ = lengthZ;
        this.strategy = strategy;
        this.seamWidth = seamWidth;
        this.regions = Collections.unmodifiableList(new ArrayList<>(regions));
        this.totalNonAir = total;
    }

    public int widthX() {
        return widthX;
    }

    public int heightY() {
        return heightY;
    }

    public int lengthZ() {
        return lengthZ;
    }

    public PartitionStrategy strategy() {
        return strategy;
    }

    public int seamWidth() {
        return seamWidth;
    }

    public int regionCount() {
        return regions.size();
    }

    public BuildRegion region(int index) {
        checkIndex(index);
        return regions.get(index);
    }

    public List<BuildRegion> regions() {
        return regions;
    }

    /** Sum of {@link BuildRegion#nonAirCount()} over all regions, i.e. the schematic's non-air cell count. */
    public long totalNonAir() {
        return totalNonAir;
    }

    /**
     * @return {@code true} if region {@code i} may only start once regions {@code 0..i-1} are finished
     * ({@link PartitionStrategy#LAYERS}: bottom-up). {@code false} means all regions are independent.
     */
    public boolean isOrdered() {
        return strategy == PartitionStrategy.LAYERS;
    }

    /** @return whether the plan was made for a schematic of exactly this size */
    public boolean fits(int widthX, int heightY, int lengthZ) {
        return this.widthX == widthX && this.heightY == heightY && this.lengthZ == lengthZ;
    }

    /**
     * @return the index of the single region owning this cell, or {@code -1} if it is outside the schematic
     */
    public int owner(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return -1;
        }
        for (BuildRegion r : regions) {
            if (r.contains(x, y, z)) {
                return r.index();
            }
        }
        return -1; // unreachable for plans from SchematicPartitioner: the boxes tile the volume
    }

    /**
     * @return whether this cell lies in a seam band (see the class doc). Always {@code false} outside the schematic
     * or with {@link #seamWidth()} 0.
     */
    public boolean isSeam(int x, int y, int z) {
        int o = owner(x, y, z);
        return o >= 0 && isSeamIn(regions.get(o), x, y, z);
    }

    private boolean isSeamIn(BuildRegion r, int x, int y, int z) {
        if (seamWidth == 0) {
            return false;
        }
        int highSide = seamWidth / 2;       // floor(w/2) band layers on the high side of a cut
        int lowSide = (seamWidth + 1) / 2;  // ceil(w/2) band layers on the low side of a cut
        return nearCut(x, r.minX(), r.maxX(), widthX, highSide, lowSide)
                || nearCut(y, r.minY(), r.maxY(), heightY, highSide, lowSide)
                || nearCut(z, r.minZ(), r.maxZ(), lengthZ, highSide, lowSide);
    }

    private static boolean nearCut(int c, int lo, int hi, int size, int highSide, int lowSide) {
        // a face is a cut (shared with another region) iff it is strictly inside the schematic
        return (lo > 0 && lo < size && c < lo + highSide) || (hi > 0 && hi < size && c >= hi - lowSide);
    }

    /** Same as {@code regionMask(index, RegionPart.WHOLE)}. */
    public StaticMask regionMask(int index) {
        return regionMask(index, RegionPart.WHOLE);
    }

    /**
     * A mask the size of the schematic selecting the given part of one region, for
     * {@link baritone.api.schematic.MaskSchematic#create}. Cells outside the schematic are never part of the mask.
     */
    public StaticMask regionMask(int index, RegionPart part) {
        checkIndex(index);
        if (part == null) {
            throw new IllegalArgumentException("part is null");
        }
        return new RegionMask(this, regions.get(index), part);
    }

    private boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < widthX && y >= 0 && y < heightY && z >= 0 && z < lengthZ;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= regions.size()) {
            throw new IllegalArgumentException("region index " + index + " out of range 0.." + (regions.size() - 1));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PartitionPlan)) {
            return false;
        }
        PartitionPlan p = (PartitionPlan) o;
        return widthX == p.widthX && heightY == p.heightY && lengthZ == p.lengthZ && strategy == p.strategy
                && seamWidth == p.seamWidth && regions.equals(p.regions);
    }

    @Override
    public int hashCode() {
        int h = widthX;
        h = 31 * h + heightY;
        h = 31 * h + lengthZ;
        h = 31 * h + strategy.hashCode();
        h = 31 * h + seamWidth;
        h = 31 * h + regions.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return "PartitionPlan{" + widthX + "x" + heightY + "x" + lengthZ + " " + strategy.name().toLowerCase()
                + " seam=" + seamWidth + " nonAir=" + totalNonAir + " " + regions + "}";
    }

    private static final class RegionMask implements StaticMask {

        private final PartitionPlan plan;
        private final BuildRegion region;
        private final RegionPart part;

        RegionMask(PartitionPlan plan, BuildRegion region, RegionPart part) {
            this.plan = plan;
            this.region = region;
            this.part = part;
        }

        @Override
        public boolean partOfMask(int x, int y, int z) {
            if (!region.contains(x, y, z)) {
                return false; // also covers out-of-bounds: region boxes are inside the schematic
            }
            switch (part) {
                case INTERIOR:
                    return !plan.isSeamIn(region, x, y, z);
                case SEAM:
                    return plan.isSeamIn(region, x, y, z);
                default:
                    return true;
            }
        }

        @Override
        public int widthX() {
            return plan.widthX;
        }

        @Override
        public int heightY() {
            return plan.heightY;
        }

        @Override
        public int lengthZ() {
            return plan.lengthZ;
        }
    }
}
