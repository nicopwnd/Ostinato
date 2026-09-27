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

/**
 * One region of a {@link PartitionPlan}: an axis-aligned box in schematic-relative coordinates,
 * {@code min} inclusive and {@code max} exclusive. A box can be empty (zero volume) when there are more bots
 * than slices to hand out; building an empty region finishes immediately.
 */
public final class BuildRegion {

    private final int index;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;
    private final long nonAirCount;

    public BuildRegion(int index, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long nonAirCount) {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("inverted region box");
        }
        this.index = index;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.nonAirCount = nonAirCount;
    }

    public int index() {
        return index;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    /** Exclusive. */
    public int maxX() {
        return maxX;
    }

    /** Exclusive. */
    public int maxY() {
        return maxY;
    }

    /** Exclusive. */
    public int maxZ() {
        return maxZ;
    }

    /** Number of non-air (buildable) cells inside this box. This is what the partitioner balances. */
    public long nonAirCount() {
        return nonAirCount;
    }

    public long volume() {
        return (long) (maxX - minX) * (maxY - minY) * (maxZ - minZ);
    }

    public boolean isEmpty() {
        return volume() == 0;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BuildRegion)) {
            return false;
        }
        BuildRegion r = (BuildRegion) o;
        return index == r.index && minX == r.minX && minY == r.minY && minZ == r.minZ
                && maxX == r.maxX && maxY == r.maxY && maxZ == r.maxZ && nonAirCount == r.nonAirCount;
    }

    @Override
    public int hashCode() {
        int h = index;
        h = 31 * h + minX;
        h = 31 * h + minY;
        h = 31 * h + minZ;
        h = 31 * h + maxX;
        h = 31 * h + maxY;
        h = 31 * h + maxZ;
        h = 31 * h + Long.hashCode(nonAirCount);
        return h;
    }

    /** Compact form, e.g. {@code r0[0,0,0:15,20,32)n=812}. */
    @Override
    public String toString() {
        return "r" + index + "[" + minX + "," + minY + "," + minZ + ":" + maxX + "," + maxY + "," + maxZ + ")n=" + nonAirCount;
    }
}
