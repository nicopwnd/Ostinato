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
 * Which horizontal axis {@link PartitionStrategy#STRIPS} cuts (and which axis {@link PartitionStrategy#GRID}
 * cuts into columns first). {@link #AUTO} picks the longer of X and Z, X on a tie. Ignored by
 * {@link PartitionStrategy#LAYERS}.
 */
public enum PartitionAxis {
    AUTO, X, Z;

    /**
     * Parses a setting value such as {@code "auto"}, {@code "x"} or {@code "Z"}.
     *
     * @throws IllegalArgumentException if the name is not a known axis
     */
    public static PartitionAxis parse(String name) {
        if (name == null) {
            throw new IllegalArgumentException("partition axis is null (expected auto|x|z)");
        }
        String n = name.trim().toUpperCase(Locale.ROOT);
        for (PartitionAxis a : values()) {
            if (a.name().equals(n)) {
                return a;
            }
        }
        throw new IllegalArgumentException("unknown partition axis '" + name + "' (expected auto|x|z)");
    }
}
