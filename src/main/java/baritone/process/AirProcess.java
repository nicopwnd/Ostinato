package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementSwim;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BubbleColumnBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Breath: when swimming low on air, path to the surface (around ceilings like a shipwreck hull,
 * which pressing jump in place cannot get past), or into a nearer bubble column (eyes inside one
 * refill air, soul sand or magma), wait there until the bar is full, then hand control back to
 * whatever process was running. In a magma (downward) column we sneak, so landing on the magma
 * block doesn't burn. With neither in reach, a wooden door placed on the floor makes an air pocket
 * (doors displace water): open it, step in so the head is in its upper half, breathe, and break it
 * again if it was the only door carried.
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
    /** Last scan found open water above: only then can a swim movement creep up to breathe. */
    private boolean surfaceInReach;
    private boolean breathed;
    private boolean breakStarted;
    private static final int NONE = Integer.MIN_VALUE;

    /** Door air pocket: the door's lower block, or null when not using one. */
    private BlockPos door;
    private boolean pickUpDoor, wasPlaced;
    private int doorTicks;

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
            surfaceInReach = surface != NONE;
            int sd = surface == NONE ? Integer.MAX_VALUE : surface - ctx.playerFeet().getY();
            col = columnGoal(sd);
            depth = Math.min(sd, colDist);
            if (depth == Integer.MAX_VALUE) depth = 0; // no air in reach: keep going, one may come into range
        }
        if (!active && ctx.player().isSwimming() && col == null && surfaceInReach && baritone.getPathingBehavior().isPathing() && air > 20) {
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
            if (c == null && surfaceY == NONE && !startDoorPocket()) {
                // Nowhere to breathe in range (roofed tunnel): stopping won't help, carry on.
                active = false;
                return false;
            }
            // startDoorPocket nulls goal; a y+64 surface goal left after the pocket drowned the bot.
            goal = door != null ? null : c != null ? c : surfaceGoal(surfaceY == NONE ? ctx.playerFeet().getY() + 64 : surfaceY);
            if (door != null) logDebug("Low on air (" + air + "), nothing to breathe in reach: door air pocket at " + door);
            else if (c != null) logDebug("Low on air (" + air + " d=" + depth + " eta=" + baritone.getPathingBehavior().estimatedTicksToGoal().map(Math::round).orElse(-1L) + "), heading to a bubble column");
            else logDebug("Low on air (" + air + "), surfacing to y=" + surfaceY);
        } else if (active && door == null && air < 60 && surfaceY != NONE && ctx.playerFeet().getY() < surfaceY - 12 && startDoorPocket()) {
            // Too deep to make the surface on what's left: breathe in a door instead.
            logDebug("Air critical (" + air + "), door air pocket at " + door);
        } else if (active && door == null && (air >= max || Math.abs(ctx.playerFeet().getX() - fromX) + Math.abs(ctx.playerFeet().getZ() - fromZ) > 48)) {
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
        if (door != null) {
            PathingCommand cmd = tickDoorPocket();
            if (cmd != null) return cmd;
            if (goal == null) {
                active = false;
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
        }
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

    private static boolean isWoodenDoor(ItemStack st) {
        return st.getItem() instanceof BlockItem && ((BlockItem) st.getItem()).getBlock() instanceof DoorBlock
                && ((BlockItem) st.getItem()).getBlock() != Blocks.IRON_DOOR;
    }

    private int doorCount() {
        int n = 0;
        for (ItemStack st : ctx.player().inventory.mainInventory) if (isWoodenDoor(st)) n += st.getCount();
        return n;
    }

    /** Picks a floor cell within reach whose two blocks are water; false when there is no door or spot. */
    private boolean startDoorPocket() {
        if (!Baritone.settings().allowDoorAirPockets.value || doorCount() == 0) return false;
        BlockPos feet = ctx.playerFeet();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -4; dy <= 1; dy++) {
                    if (dx == 0 && dz == 0) continue; // never in our own cell: we'd block the placement
                    BlockPos p = feet.add(dx, dy, dz);
                    if (!MovementHelper.isWater(ctx.world().getBlockState(p)) || !MovementHelper.isWater(ctx.world().getBlockState(p.up()))) continue;
                    BlockPos under = p.down();
                    if (!ctx.world().getBlockState(under).isSolidSide(ctx.world(), under, Direction.UP)) continue;
                    double d = ctx.player().getDistanceSq(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                    if (d < bestD && d < 16) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        if (best == null) return false;
        door = best;
        pickUpDoor = doorCount() == 1;
        wasPlaced = false;
        breathed = false;
        breakStarted = false;
        doorTicks = 0;
        active = true;
        goal = null;
        return true;
    }

    /** Place, open, enter, breathe, then (only door carried) break it back. Null once finished or abandoned. */
    private PathingCommand tickDoorPocket() {
        BlockState st = ctx.world().getBlockState(door);
        boolean placed = st.getBlock() instanceof DoorBlock && st.get(DoorBlock.HALF) == net.minecraft.state.properties.DoubleBlockHalf.LOWER;
        boolean inside = ctx.playerFeet().equals(door);
        wasPlaced |= placed;
        // Breaking a door by hand underwater takes ~450 ticks, so the break gets its own budget.
        if (++doorTicks > (breathed ? 1200 : 600) || (!wasPlaced && doorTicks > 100)) {
            logDebug("Door air pocket failed");
            if (breakStarted) ctx.playerController().resetBlockRemoving();
            door = null;
            return null;
        }
        PathingCommand pause = new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        if (!placed) {
            if (wasPlaced) {
                // Broken back: wait for the dropped door to reach us (it drops at our feet).
                if (doorCount() > 0 || ctx.player().getAir() < 60) { door = null; return null; }
                // The drop drifts underwater; swim to it rather than waiting in place (TenorClef door course rep 1 drowned waiting).
                ItemEntity drop = null;
                for (ItemEntity e : ctx.world().getEntitiesWithinAABB(ItemEntity.class, new AxisAlignedBB(door).grow(6), e -> isWoodenDoor(e.getItem()))) {
                    if (drop == null || ctx.player().getDistanceSq(e) < ctx.player().getDistanceSq(drop)) drop = e;
                }
                if (drop != null) {
                    Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), drop.getPositionVec(), ctx.playerRotations());
                    baritone.getLookBehavior().updateTarget(new Rotation(r.getYaw(), 0), false);
                    double dx = drop.getPosX() - ctx.player().getPosX(), dz = drop.getPosZ() - ctx.player().getPosZ();
                    // Drops float to the ceiling; walking into the spot under it pins us to the floor, so only close in horizontally.
                    if (dx * dx + dz * dz > 0.25) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                    if (drop.getPosY() < ctx.player().getPosY() - 0.3) baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                    else if (drop.getPosY() > ctx.player().getPosY() + 0.5) baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                }
                return pause;
            }
            if (inside) {
                // Drifted into the target before placing: the door can't go in our own cell, pick another.
                int t = doorTicks;
                if (!startDoorPocket()) { door = null; return null; }
                doorTicks = t;
                return pause;
            }
            BlockPos under = door.down();
            Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, under, new Vector3d(door.getX() + 0.5, door.getY(), door.getZ() + 0.5), ctx.playerController().getBlockReachDistance(), false);
            if (rot.isPresent() && baritone.getInventoryBehavior().throwaway(true, AirProcess::isWoodenDoor)) {
                // Click on the live crosshair, not the target ray: clicking before the turn landed put doors
                // on other cells (TenorClef door course: door gone from inventory, none at the target).
                RayTraceResult r = ctx.objectMouseOver();
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                if (r instanceof BlockRayTraceResult && ((BlockRayTraceResult) r).getFace() == Direction.UP && ((BlockRayTraceResult) r).getPos().equals(under)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                }
            }
            return pause;
        }
        if (!st.get(DoorBlock.OPEN)) {
            Optional<Rotation> rot = RotationUtils.reachable(ctx, door);
            if (rot.isPresent()) {
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                if (ctx.isLookingAt(door)) baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
            return pause;
        }
        if (!inside) {
            // Swim into the open door: face its centre, forward, sneak down if above it.
            Vector3d c = new Vector3d(door.getX() + 0.5, door.getY(), door.getZ() + 0.5);
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), c, ctx.playerRotations());
            baritone.getLookBehavior().updateTarget(new Rotation(r.getYaw(), 0), false);
            double hx = c.x - ctx.player().getPosX(), hz = c.z - ctx.player().getPosZ();
            if (hx * hx + hz * hz > 0.04) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            if (ctx.player().getPosY() > door.getY() + 0.1) baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            return pause;
        }
        if (ctx.player().getAir() < ctx.player().getMaxAir()) return pause; // breathing
        if (!breathed) { breathed = true; doorTicks = 0; }
        if (!pickUpDoor) {
            door = null; // leave it as an air station
            return null;
        }
        // The eye sits inside the doorway, so a crosshair ray passes the open panel and hits the wall behind
        // (TenorClef door course: 1200 ticks clicking walls). Face the panel and drive the controller directly;
        // breaking must happen in here, with eyes out of water, or it takes 5x longer than a full air bar.
        // Break the lower half: its drop spawns inside our pickup box. The upper half's drop spawned at the edge
        // of it and floated to the ceiling out of reach (TenorClef door course rep 1 drowned under it).
        BlockPos top = door;
        baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.calculateBlockCenter(ctx.world(), top), ctx.playerRotations()), true);
        if (!breakStarted) {
            ctx.playerController().syncHeldItem();
            ctx.playerController().clickBlock(top, Direction.UP);
            breakStarted = true;
        }
        ctx.playerController().onPlayerDamageBlock(top, Direction.UP);
        ctx.player().swingArm(Hand.MAIN_HAND);
        return pause;
    }

    @Override
    public void onLostControl() {
        // A caller cancelling every tick must not reset surfacing: that rebuilt the goal and restarted
        // the search each tick at spd 0 until drowning (TenorClef s326o). Keep it while still short of air.
        if (active && door == null && goal != null && ctx.player() != null && ctx.player().isInWater() && ctx.player().getAir() < ctx.player().getMaxAir()) return;
        active = false;
        door = null;
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
