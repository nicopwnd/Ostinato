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

import java.util.Locale;

/**
 * How {@link SchematicPartitioner} cuts a schematic into regions for several bots.
 * <p>
 * Every strategy produces axis-aligned boxes that tile the whole schematic volume (air included, so each
 * air cell that has to be cleared also has exactly one owner). Cuts are placed so that every region gets
 * about the same number of non-air cells, not the same volume.
 */
public enum PartitionStrategy {

    /**
     * Full-height strips along the longest horizontal axis (or {@code buildPartitionAxis}). Regions are
     * independent: all support for a block is in its own column, so strips can be built in any order.
     */
    STRIPS,

    /**
     * Full-height columns x rows. The first horizontal axis is cut into columns, then each column is cut
     * into rows along the other axis, so any bot count works (5 bots = 3 columns of 2, 2 and 1 rows...
     * whatever balances best). Regions are independent, like strips.
     */
    GRID,

    /**
     * Horizontal Y bands, region 0 at the bottom. <b>Not independent:</b> a band sits on the band below it,
     * so band {@code i} must be finished before band {@code i + 1} starts ({@link PartitionPlan#isOrdered()}).
     */
    LAYERS;

    /**
     * Parses a setting value such as {@code "strips"}, {@code "Grid"} or {@code "layers"}.
     *
     * @throws IllegalArgumentException if the name is not a known strategy
     */
    public static PartitionStrategy parse(String name) {
        if (name == null) {
            throw new IllegalArgumentException("partition strategy is null (expected strips|grid|layers)");
        }
        String n = name.trim().toUpperCase(Locale.ROOT);
        for (PartitionStrategy s : values()) {
            if (s.name().equals(n)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown partition strategy '" + name + "' (expected strips|grid|layers)");
    }
}
