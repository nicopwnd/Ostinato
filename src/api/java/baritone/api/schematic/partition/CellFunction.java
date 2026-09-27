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
 * Maps a schematic cell to a material key (for example a {@code Block}) for
 * {@link SchematicPartitioner#materials(PartitionPlan, int, RegionPart, CellFunction)}.
 *
 * @param <K> the material key type
 */
@FunctionalInterface
public interface CellFunction<K> {

    /**
     * @return the material that has to be placed at this cell, or {@code null} for air / cells that don't matter
     */
    K at(int x, int y, int z);
}
