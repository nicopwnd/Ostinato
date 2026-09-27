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

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import net.minecraft.block.AirBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

import java.util.Collections;
import java.util.Map;

/**
 * Minecraft adapter for {@link SchematicPartitioner}: reads what a real {@link ISchematic} wants at each cell.
 * <p>
 * A cell counts as "non-air" if the schematic cares about it ({@code inSchematic}) and wants a block that is not an
 * {@link AirBlock} there, judged as if the world were empty (current state = air). Partition the schematic
 * <i>before</i> rotation/mirroring: {@code IBuilderProcess.build} applies {@code buildSchematicRotation/Mirror} on
 * top of the masked schematic, so every bot with the same settings maps the plan the same way.
 */
public final class SchematicCells {

    private SchematicCells() {}

    /** @return the state the schematic wants at a cell, or {@code null} if it doesn't care about that cell */
    public static BlockState desired(ISchematic schematic, int x, int y, int z) {
        BlockState air = Blocks.AIR.getDefaultState();
        if (!schematic.inSchematic(x, y, z, air)) {
            return null;
        }
        if (schematic instanceof IStaticSchematic) {
            return ((IStaticSchematic) schematic).getDirect(x, y, z);
        }
        return schematic.desiredState(x, y, z, air, Collections.emptyList());
    }

    /** @return whether the builder has to place a block here */
    public static boolean isBuildable(BlockState state) {
        return state != null && !(state.getBlock() instanceof AirBlock);
    }

    public static CellSource occupancy(ISchematic schematic) {
        return (x, y, z) -> isBuildable(desired(schematic, x, y, z));
    }

    /**
     * Partition with the {@code buildPartition*} settings ({@code buildPartitionStrategy}, {@code buildPartitionSeamWidth},
     * {@code buildPartitionAxis}, {@code buildPartitionGridColumns}).
     *
     * @throws IllegalArgumentException if a setting holds an unknown value
     */
    public static PartitionPlan partition(ISchematic schematic, int regions) {
        Settings s = BaritoneAPI.getSettings();
        return partition(schematic, regions, PartitionStrategy.parse(s.buildPartitionStrategy.value),
                s.buildPartitionSeamWidth.value, PartitionAxis.parse(s.buildPartitionAxis.value), s.buildPartitionGridColumns.value);
    }

    public static PartitionPlan partition(ISchematic schematic, int regions, PartitionStrategy strategy, int seamWidth,
                                          PartitionAxis axis, int gridColumns) {
        return SchematicPartitioner.partition(schematic.widthX(), schematic.heightY(), schematic.lengthZ(),
                occupancy(schematic), regions, strategy, seamWidth, axis, gridColumns);
    }

    /**
     * Block type to count for one region (non-air cells only), for material allocation. Deterministic, so every bot
     * can compute any region's list locally from the same file.
     */
    public static Map<Block, Integer> materials(ISchematic schematic, PartitionPlan plan, int region, RegionPart part) {
        requireFits(schematic, plan);
        return SchematicPartitioner.materials(plan, region, part, (x, y, z) -> {
            BlockState state = desired(schematic, x, y, z);
            return isBuildable(state) ? state.getBlock() : null;
        });
    }

    public static Map<Block, Integer> materials(ISchematic schematic, PartitionPlan plan, int region) {
        return materials(schematic, plan, region, RegionPart.WHOLE);
    }

    /** @throws IllegalArgumentException if the plan was made for a schematic of another size */
    public static void requireFits(ISchematic schematic, PartitionPlan plan) {
        if (!plan.fits(schematic.widthX(), schematic.heightY(), schematic.lengthZ())) {
            throw new IllegalArgumentException("partition plan is for " + plan.widthX() + "x" + plan.heightY() + "x" + plan.lengthZ()
                    + " but the schematic is " + schematic.widthX() + "x" + schematic.heightY() + "x" + schematic.lengthZ());
        }
    }
}
