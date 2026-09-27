package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementSwim;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BubbleColumnBlock;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * Breath: when swimming low on air, path to the surface (around ceilings like a shipwreck hull,
 * which pressing jump in place cannot get past), or into a nearer bubble column (eyes inside one
 * refill air, soul sand or magma), wait there until the bar is full, then hand control back to
 * whatever process was running. In a magma (downward) column we sneak, so landing on the magma
 * block doesn't burn.
 */
public final class AirProcess extends BaritoneProcessHelper {

    private boolean active;
    private int surfaceY;
    private int depth;
    /** Where surfacing began: a teleport (or long drift) makes the goal stale. */
    private int fromX, fromZ;
    /** Built once per surfacing: a fresh Goal each tick would look like a goal change and restart the search. */
    private Goal goal;
    private Goal col;
    private int colDist;
    private static final int NONE = Integer.MIN_VALUE;

    public AirProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (ctx.player() == null || ctx.world() == null || !Baritone.settings().swimInWater.value) {
            return active = false;
        }
        int air = ctx.player().getAir(), max = ctx.player().getMaxAir();
        if (ctx.world().getBlockState(new BlockPos(ctx.player().getPositionVec()).down()).getBlock() == Blocks.MAGMA_BLOCK
                || (ctx.player().isInWater() && ctx.world().getBlockState(ctx.playerFeet().down()).getBlock() == Blocks.MAGMA_BLOCK)) {
            // Whatever process is driving: sneaking is the only thing that stops magma burning us.
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        }
        if (!active && ctx.player().isInWater() && ctx.player().ticksExisted % 20 == 0) {
            // Blocks to the nearest breathable spot: open surface straight up, or a bubble column.
            int surface = findSurfaceY();
            int sd = surface == NONE ? Integer.MAX_VALUE : surface - ctx.playerFeet().getY();
            col = columnGoal(sd);
            depth = Math.min(sd, colDist);
            if (depth == Integer.MAX_VALUE) depth = 0; // no air in reach: keep going, one may come into range
        }
        if (!active && ctx.player().isSwimming() && col == null && baritone.getPathingBehavior().isPathing() && air > 20) {
            // Swimming under open water: the movement creeps up to breathe without leaving the swim pose.
            return false;
        }
        if (!active && ctx.player().isInWater() && air < Math.min(max - 40, Math.max(max / 3, depth * 9)) && !goalWithinBreath(air, depth)) {
            // ~1.5x the straight swim (4 ticks/block): paths detour around hulls and walls.
            active = true;
            fromX = ctx.playerFeet().getX();
            fromZ = ctx.playerFeet().getZ();
            surfaceY = findSurfaceY();
            Goal c = columnGoal(surfaceY == NONE ? Integer.MAX_VALUE : surfaceY - ctx.playerFeet().getY());
            if (c == null && surfaceY == NONE) {
                // Nowhere to breathe in range (roofed tunnel): stopping won't help, carry on.
                active = false;
                return false;
            }
            goal = c != null ? c : surfaceGoal(surfaceY == NONE ? ctx.playerFeet().getY() + 64 : surfaceY);
            if (c != null) logDebug("Low on air (" + air + " d=" + depth + " eta=" + baritone.getPathingBehavior().estimatedTicksToGoal().map(Math::round).orElse(-1L) + "), heading to a bubble column");
            else logDebug("Low on air (" + air + "), surfacing to y=" + surfaceY);
        } else if (active && (air >= max || Math.abs(ctx.playerFeet().getX() - fromX) + Math.abs(ctx.playerFeet().getZ() - fromZ) > 48)) {
            active = false;
        }
        return active;
    }

    /** The current task's goal is closer than turning back for air (with a margin for slow swimming). */
    private boolean goalWithinBreath(int air, int airDist) {
        // Also press on when the goal is nearer than the air behind us: turning back can only be worse.
        return baritone.getPathingBehavior().isPathing()
                && baritone.getPathingBehavior().estimatedTicksToGoal().map(t -> t * 1.3 + 20 < air || t * 1.3 < airDist * 9).orElse(false);
    }

    /** Top of the water nearby: highest y over a 9x9 area whose block is water with a non-water block above. */
    private int findSurfaceY() {
        BlockPos feet = ctx.playerFeet();
        int best = NONE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int y = feet.getY(); y < feet.getY() + 64 && y < 255; y++) {
                    BlockPos p = new BlockPos(feet.getX() + dx, y, feet.getZ() + dz);
                    BlockState st = ctx.world().getBlockState(p);
                    if (!MovementHelper.isWater(st)) {
                        // a roof (glass, ice, a hull) is not a surface: there must be air to breathe
                        if (y - 1 > best && y > feet.getY() && st.getCollisionShape(ctx.world(), p).isEmpty() && st.getFluidState().isEmpty()) best = y - 1;
                        break;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Bubble-column cells (with column above, so the eyes are in it too) within 12 blocks that are
     * closer than the surface; null if none. A roofed-over tunnel has no reachable surface at all.
     */
    private Goal columnGoal(int surfaceDist) {
        BlockPos feet = ctx.playerFeet();
        int best = surfaceDist == Integer.MAX_VALUE ? Integer.MAX_VALUE : surfaceDist + 2;
        colDist = Integer.MAX_VALUE;
        Set<BlockPos> cells = new HashSet<>();
        for (int dx = -40; dx <= 40; dx++) {
            for (int dz = -40; dz <= 40; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos p = feet.add(dx, dy, dz);
                    if (column(p) && column(p.up())) {
                        int d = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (d < best) {
                            cells.add(p.toImmutable());
                            colDist = Math.min(colDist, d);
                        }
                    }
                }
            }
        }
        if (cells.isEmpty()) return null;
        return new Goal() {
            @Override
            public boolean isInGoal(int x, int y, int z) {
                return cells.contains(new BlockPos(x, y, z));
            }

            @Override
            public double heuristic(int x, int y, int z) {
                double h = Double.MAX_VALUE;
                for (BlockPos c : cells) h = Math.min(h, Math.abs(c.getX() - x) + Math.abs(c.getY() - y) + Math.abs(c.getZ() - z));
                return h * MovementSwim.SWIM_ONE_BLOCK_COST / 2;
            }

            @Override
            public String toString() {
                return "BubbleColumn{" + cells.size() + "}";
            }
        };
    }

    private boolean column(BlockPos p) {
        return ctx.world().getBlockState(p).getBlock() == Blocks.BUBBLE_COLUMN;
    }

    private static Goal surfaceGoal(int target) {
        return new Goal() {
            @Override
            public boolean isInGoal(int x, int y, int z) {
                return y >= target;
            }

            @Override
            public double heuristic(int x, int y, int z) {
                return Math.max(0, target - y) * MovementSwim.SWIM_ONE_BLOCK_COST;
            }

            @Override
            public String toString() {
                return "Surface{y=" + target + "}";
            }
        };
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        BlockPos eyes = new BlockPos(ctx.player().getEyePosition(1));
        BlockState below = ctx.world().getBlockState(ctx.playerFeet().down());
        if (below.getBlock() == Blocks.MAGMA_BLOCK) {
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true); // no burn while sneaking
        }
        if (column(eyes)) {
            // Breathing in the column. A magma column drags us onto the magma: sneak the whole way.
            BlockState c = ctx.world().getBlockState(eyes);
            if (c.get(BubbleColumnBlock.DRAG)) baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            return new PathingCommand(goal, PathingCommandType.REQUEST_PAUSE);
        }
        if (goal.isInGoal(ctx.playerFeet())) {
            // Bob at the surface while breathing; holding jump keeps the head out of the water.
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            return new PathingCommand(goal, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        active = false;
    }

    @Override
    public String displayName0() {
        return "Surfacing for air";
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public double priority() {
        return 4;
    }
}
