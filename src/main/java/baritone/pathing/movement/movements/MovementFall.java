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

package baritone.pathing.movement.movements;

import baritone.utils.BoatUtil;
import baritone.altoclef.AltoClefSettings;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.MovementState.MovementTarget;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LadderBlock;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.WaterFluid;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.BoatEntity;
import net.minecraft.item.BoatItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.item.Items;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3i;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class MovementFall extends Movement {

    private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);
    private static final ItemStack STACK_BUCKET_EMPTY = new ItemStack(Items.BUCKET);

    /** True while a boat fall is driving; BoatProcess keeps its hands off until we land. */
    public static volatile boolean boatRide;
    private Boolean boatMode;
    private int boatTicks;

    public MovementFall(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, MovementFall.buildPositionsToBreak(src, dest));
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult result = new MutableMoveResult();
        MovementDescend.cost(context, src.x, src.y, src.z, dest.x, dest.z, result);
        if (result.y != dest.y) {
            return COST_INF; // doesn't apply to us, this position is a descend not a fall
        }
        return result.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        set.add(src);
        for (int y = src.y - dest.y; y >= 0; y--) {
            set.add(dest.up(y));
        }
        return set;
    }

    private boolean willPlaceBucket() {
        CalculationContext context = new CalculationContext(baritone);
        MutableMoveResult result = new MutableMoveResult();
        return MovementDescend.dynamicFallCost(context, src.x, src.y, src.z, dest.x, dest.z, 0, context.get(dest.x, src.y - 2, dest.z), result);
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        if (boatMode == null) {
            CalculationContext c = new CalculationContext(baritone);
            boatMode = willPlaceBucket() && !(c.hasWaterBucket && src.y - dest.y <= c.maxFallHeightBucket + 1)
                    && !MovementHelper.isWater(ctx.world().getBlockState(dest));
        }
        if (boatMode) {
            return boatFall(state);
        }
        BlockPos playerFeet = ctx.playerFeet();
        Rotation toDest = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations());
        Rotation targetRotation = null;
        BlockState destState = ctx.world().getBlockState(dest);
        Block destBlock = destState.getBlock();
        boolean isWater = destState.getFluidState().getFluid() instanceof WaterFluid;
        if (!isWater && willPlaceBucket() && !playerFeet.equals(dest) && !AltoClefSettings.getInstance().shouldNotPlaceBucketButStillFall()) {
            if (!PlayerInventory.isHotbar(ctx.player().inventory.getSlotFor(STACK_BUCKET_WATER)) || ctx.world().getDimensionKey() == World.THE_NETHER) {
                logDebug("fall: needs a water bucket, boatMode=" + boatMode);
                return state.setStatus(MovementStatus.UNREACHABLE);
            }

            if (ctx.player().getPositionVec().y - dest.getY() < ctx.playerController().getBlockReachDistance() && !ctx.player().isOnGround()) {
                ctx.player().inventory.currentItem = ctx.player().inventory.getSlotFor(STACK_BUCKET_WATER);

                targetRotation = new Rotation(toDest.getYaw(), 90.0F);

                if (ctx.isLookingAt(dest) || ctx.isLookingAt(dest.down())) {
                    state.setInput(Input.CLICK_RIGHT, true);
                }
            }
        }
        if (targetRotation != null) {
            state.setTarget(new MovementTarget(targetRotation, true));
        } else {
            state.setTarget(new MovementTarget(toDest, false));
        }
        if (playerFeet.equals(dest) && (ctx.player().getPositionVec().y - playerFeet.getY() < 0.094 || isWater)) { // 0.094 because lilypads
            if (isWater) { // only match water, not flowing water (which we cannot pick up with a bucket)
                if (PlayerInventory.isHotbar(ctx.player().inventory.getSlotFor(STACK_BUCKET_EMPTY))) {
                    ctx.player().inventory.currentItem = ctx.player().inventory.getSlotFor(STACK_BUCKET_EMPTY);
                    if (ctx.player().getMotion().y >= 0) {
                        return state.setInput(Input.CLICK_RIGHT, true);
                    } else {
                        return state;
                    }
                } else {
                    if (ctx.player().getMotion().y >= 0) {
                        return state.setStatus(MovementStatus.SUCCESS);
                    } // don't else return state; we need to stay centered because this water might be flowing under the surface
                }
            } else {
                return state.setStatus(MovementStatus.SUCCESS);
            }
        }
        Vector3d destCenter = VecUtils.getBlockPosCenter(dest); // we are moving to the 0.5 center not the edge (like if we were falling on a ladder)
        if (Math.abs(ctx.player().getPositionVec().x + ctx.player().getMotion().x - destCenter.x) > 0.1 || Math.abs(ctx.player().getPositionVec().z + ctx.player().getMotion().z - destCenter.z) > 0.1) {
            if (!ctx.player().isOnGround() && Math.abs(ctx.player().getMotion().y) > 0.4) {
                state.setInput(Input.SNEAK, true);
            }
            state.setInput(Input.MOVE_FORWARD, true);
        }
        Vector3i avoid = Optional.ofNullable(avoid()).map(Direction::getDirectionVec).orElse(null);
        if (avoid == null) {
            avoid = src.subtract(dest);
        } else {
            double dist = Math.abs(avoid.getX() * (destCenter.x - avoid.getX() / 2.0 - ctx.player().getPositionVec().x)) + Math.abs(avoid.getZ() * (destCenter.z - avoid.getZ() / 2.0 - ctx.player().getPositionVec().z));
            if (dist < 0.6) {
                state.setInput(Input.MOVE_FORWARD, true);
            } else if (!ctx.player().isOnGround()) {
                state.setInput(Input.SNEAK, false);
            }
        }
        if (targetRotation == null) {
            Vector3d destCenterOffset = new Vector3d(destCenter.x + 0.125 * avoid.getX(), destCenter.y, destCenter.z + 0.125 * avoid.getZ());
            state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), destCenterOffset, ctx.playerRotations()), false));
        }
        return state;
    }

    /**
     * Too high to fall on foot: put a boat down where we stand, get in and drive off the edge. The boat
     * soaks up the landing; once down, BoatProcess climbs out, breaks it and picks it back up.
     */
    private MovementState boatFall(MovementState state) {
        Entity v = ctx.player().getRidingEntity();
        if (v instanceof BoatEntity) {
            boatRide = true;
            BoatUtil.restore(ctx);
            if (v.isOnGround() && Math.abs(v.getPosY() - dest.getY()) < 0.7) {
                boatRide = false;
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (!v.isOnGround()) {
                return state; // falling; nothing to steer
            }
            double tx = dest.getX() + 0.5 - v.getPosX(), tz = dest.getZ() + 0.5 - v.getPosZ();
            float want = (float) (MathHelper.atan2(tz, tx) * 180 / Math.PI) - 90;
            float diff = MathHelper.wrapDegrees(want - v.rotationYaw);
            if (diff > 4) state.setInput(Input.MOVE_RIGHT, true);
            if (diff < -4) state.setInput(Input.MOVE_LEFT, true);
            if (Math.abs(diff) < 50) state.setInput(Input.MOVE_FORWARD, true);
            if (boatTicks++ > 200) {
                boatRide = false;
                logDebug("boat fall: stuck riding at edge " + ctx.player().getPositionVec() + " ticks=" + boatTicks);
            return state.setStatus(MovementStatus.UNREACHABLE);
            }
            return state.setTarget(new MovementTarget(new Rotation(want, 10), true));
        }
        boatRide = false;
        if (nearSrc() > 2 && ctx.player().isOnGround() && boatTicks < 60 && ctx.playerFeet().getY() >= src.getY()) {
            // Handed the movement a step early/late: walk back onto src first.
            boatTicks++;
            state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(src), ctx.playerRotations()), false));
            return state.setInput(Input.MOVE_FORWARD, true);
        }
        if (nearSrc() > 2 || boatTicks++ > 160) {
            logDebug("boat fall: couldn't place/mount " + ctx.player().getPositionVec() + " src=" + src + " dest=" + dest + " ticks=" + boatTicks);
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        Entity boat = null;
        for (Entity e : ctx.entities()) {
            if (e instanceof BoatEntity && e.getPassengers().isEmpty() && ctx.player().getDistance(e) < 3
                    && (boat == null || ctx.player().getDistance(e) < ctx.player().getDistance(boat))) boat = e;
        }
        if (boat != null) {
            state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vector3d(boat.getPosX(), boat.getPosY() + 0.3, boat.getPosZ()), ctx.playerRotations()), true));
            if (boatTicks % 4 == 3) Minecraft.getInstance().playerController.interactWithEntity(ctx.player(), boat, Hand.MAIN_HAND);
            return state;
        }
        int slot = BoatUtil.hotbarBoat(ctx);
        if (slot < 0) {
            logDebug("boat fall: no boat " + ctx.player().getPositionVec() + " ticks=" + boatTicks);
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        ctx.player().inventory.currentItem = slot;
        // Boats can't overlap us. With headroom, jump and place underneath at the top of the jump;
        // in a 1-2 high space, back off src and place on its far half instead.
        if (ctx.playerFeet().equals(src) && MovementHelper.canWalkThrough(ctx, src.up(2)) && MovementHelper.canWalkThrough(ctx, src.up(3))) {
            state.setTarget(new MovementTarget(new Rotation(ctx.playerRotations().getYaw(), 90), true));
            if (ctx.player().isOnGround()) {
                state.setInput(Input.JUMP, true);
            } else if (ctx.player().getPosY() - src.getY() > 0.7) {
                Minecraft.getInstance().playerController.processRightClick(ctx.player(), ctx.world(), Hand.MAIN_HAND);
            }
            return state;
        }
        double dx = Math.signum(dest.getX() - src.getX()), dz = Math.signum(dest.getZ() - src.getZ());
        Vector3d aim = new Vector3d(src.getX() + 0.5 + dx * 0.35, src.getY(), src.getZ() + 0.5 + dz * 0.35);
        state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aim, ctx.playerRotations()), true));
        double back = (src.getX() + 0.5 - ctx.player().getPosX()) * dx + (src.getZ() + 0.5 - ctx.player().getPosZ()) * dz;
        if (back < 0.8) {
            state.setInput(Input.MOVE_BACK, true);
        } else if (boatTicks % 5 == 4) {
            Minecraft.getInstance().playerController.processRightClick(ctx.player(), ctx.world(), Hand.MAIN_HAND);
        }
        return state;
    }

    // Block-distance² from src (Vector3i.distanceSq offsets one side by 0.5, which trips on a jump).
    private int nearSrc() {
        BlockPos f = ctx.playerFeet();
        int x = f.getX() - src.getX(), y = f.getY() - src.getY(), z = f.getZ() - src.getZ();
        return x * x + y * y + z * z;
    }

    private Direction avoid() {
        for (int i = 0; i < 15; i++) {
            BlockState state = ctx.world().getBlockState(ctx.playerFeet().down(i));
            if (state.getBlock() == Blocks.LADDER) {
                return state.get(LadderBlock.FACING);
            }
        }
        return null;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // if we haven't started walking off the edge yet, or if we're in the process of breaking blocks before doing the fall
        // then it's safe to cancel this
        if (ctx.player().getRidingEntity() instanceof BoatEntity) {
            return state.getStatus() != MovementStatus.RUNNING;
        }
        return ctx.playerFeet().equals(src) || state.getStatus() != MovementStatus.RUNNING;
    }

    private static BetterBlockPos[] buildPositionsToBreak(BetterBlockPos src, BetterBlockPos dest) {
        BetterBlockPos[] toBreak;
        int diffX = src.getX() - dest.getX();
        int diffZ = src.getZ() - dest.getZ();
        int diffY = src.getY() - dest.getY();
        toBreak = new BetterBlockPos[diffY + 2];
        for (int i = 0; i < toBreak.length; i++) {
            toBreak[i] = new BetterBlockPos(src.getX() - diffX, src.getY() + 1 - i, src.getZ() - diffZ);
        }
        return toBreak;
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        // only break if one of the first three needs to be broken
        // specifically ignore the last one which might be water
        for (int i = 0; i < 4 && i < positionsToBreak.length; i++) {
            if (!MovementHelper.canWalkThrough(ctx, positionsToBreak[i])) {
                return super.prepared(state);
            }
        }
        return true;
    }
}
