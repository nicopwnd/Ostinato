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
 * Pure occupancy view of a schematic, so partitioning can run (and be unit tested) without Minecraft.
 * Coordinates are relative to the schematic origin and always inside its bounds.
 * {@link SchematicCells#occupancy(baritone.api.schematic.ISchematic)} adapts a real schematic.
 */
@FunctionalInterface
public interface CellSource {

    /**
     * @return {@code true} if the cell holds a block that has to be placed (anything but air)
     */
    boolean occupied(int x, int y, int z);
}
