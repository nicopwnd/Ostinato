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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MoverType;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovementInputFromOptions;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;

import java.util.Optional;

/**
 * Detached camera for picking a destination. Walks with gravity or, after a double-tapped jump, flies like creative flight; collides with blocks; and is clamped to the bot's render distance.
 * Left click: follow the entity under the crosshair, else travel to the block under it.
 * Right click: travel to the camera's own position.
 */
public final class FreecamBehavior extends Behavior implements Helper {

    /** Active camera of the primary bot, read by MixinMouseHelper to steer the camera instead of the player. */
    private static Camera active;

    private Camera camera;
    private boolean flying;
    private boolean jumpHeld;
    private int jumpTapTicks;

    public FreecamBehavior(Baritone baritone) {
        super(baritone);
    }

    public static Entity activeCamera() {
        return active;
    }

    public boolean isActive() {
        return camera != null;
    }

    public void toggle() {
        if (camera == null) {
            enable();
        } else {
            disable();
        }
    }

    public void enable() {
        ClientPlayerEntity p = ctx.player();
        if (camera != null || p == null || baritone != BaritoneAPI.getProvider().getPrimaryBaritone()) {
            return;
        }
        camera = new Camera(p.world);
        flying = p.abilities.isFlying;
        jumpHeld = true;
        jumpTapTicks = 0;
        camera.setPositionAndRotation(p.getPosX(), p.getPosY(), p.getPosZ(), p.rotationYaw, p.rotationPitch);
        camera.syncPrev();
        active = camera;
        mc.setRenderViewEntity(camera);
        p.movementInput = new MovementInput();
        logDirect("Freecam on. Left click: go to block / follow entity. Right click: go to camera. #freecam to exit.");
    }

    public void disable() {
        if (camera == null) {
            return;
        }
        camera = null;
        active = null;
        ClientPlayerEntity p = ctx.player();
        if (p != null) {
            mc.setRenderViewEntity(p);
            if (p.movementInput.getClass() == MovementInput.class) {
                p.movementInput = new MovementInputFromOptions(mc.gameSettings);
            }
        }
        logDirect("Freecam off");
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (camera != null) {
            disable();
        }
    }

    @Override
    public void onTick(TickEvent event) {
        if (camera == null) {
            return;
        }
        ClientPlayerEntity p = ctx.player();
        if (event.getType() == TickEvent.Type.OUT || p == null || camera.world != p.world) {
            disable();
            return;
        }
        // Keys drive the camera, never the bot; the pathing input (PlayerMovementInput) is left alone.
        if (p.movementInput.getClass() == MovementInputFromOptions.class) {
            p.movementInput = new MovementInput();
        }
        if (mc.getRenderViewEntity() != camera) {
            mc.setRenderViewEntity(camera);
        }
        move(mc.gameSettings);
        clamp(p);
        if (mc.currentScreen == null) {
            handleClicks(mc.gameSettings);
        }
    }

    /**
     * Walks like a survival player (gravity, jump, sprint) with block collision; double-tap jump
     * toggles creative flight, as in creative mode. Approximates vanilla player physics.
     */
    private void move(GameSettings gs) {
        camera.syncPrev();
        boolean jump = gs.keyBindJump.isKeyDown();
        if (jump && !jumpHeld) {
            flying = jumpTapTicks > 0 ? !flying : flying;
            jumpTapTicks = 7;
        }
        jumpHeld = jump;
        if (jumpTapTicks > 0) {
            jumpTapTicks--;
        }
        double fwd = (gs.keyBindForward.isKeyDown() ? 1 : 0) - (gs.keyBindBack.isKeyDown() ? 1 : 0);
        double strafe = (gs.keyBindLeft.isKeyDown() ? 1 : 0) - (gs.keyBindRight.isKeyDown() ? 1 : 0);
        boolean sneak = gs.keyBindSneak.isKeyDown();
        double len = Math.sqrt(fwd * fwd + strafe * strafe);
        if (len > 1) {
            fwd /= len;
            strafe /= len;
        }
        boolean sprint = gs.keyBindSprint.isKeyDown() && fwd > 0;
        double speed = Baritone.settings().freecamSpeed.value;
        boolean onGround = camera.isOnGround();
        double accel;
        if (flying) {
            accel = 0.05 * (sprint ? 2 : 1);
        } else if (onGround) {
            accel = 0.1 * (sprint ? 1.3 : 1) * (sneak ? 0.3 : 1) * 0.21600002 / (0.6 * 0.6 * 0.6);
        } else {
            accel = sprint ? 0.026 : 0.02;
        }
        accel *= speed;
        float yaw = camera.rotationYaw * ((float) Math.PI / 180F);
        double sin = MathHelper.sin(yaw);
        double cos = MathHelper.cos(yaw);
        Vector3d m = camera.getMotion();
        double mx = m.x + (strafe * cos - fwd * sin) * accel;
        double mz = m.z + (fwd * cos + strafe * sin) * accel;
        double my = m.y;
        if (flying) {
            my += ((jump ? 1 : 0) - (sneak ? 1 : 0)) * 0.15 * speed;
        } else if (jump && onGround) {
            my = 0.42;
            if (sprint) {
                mx -= sin * 0.2;
                mz += cos * 0.2;
            }
        }
        Vector3d want = new Vector3d(mx, my, mz);
        camera.move(MoverType.SELF, want);
        Vector3d moved = new Vector3d(camera.getPosX() - camera.prevPosX, camera.getPosY() - camera.prevPosY, camera.getPosZ() - camera.prevPosZ);
        if (Math.abs(moved.x - want.x) > 1e-4) {
            mx = 0;
        }
        if (Math.abs(moved.z - want.z) > 1e-4) {
            mz = 0;
        }
        if (Math.abs(moved.y - want.y) > 1e-4) {
            my = 0;
        }
        if (flying) {
            if (camera.isOnGround() && !jump) {
                flying = false; // landing ends flight, as in creative
            }
            camera.setMotion(mx * 0.91, my * 0.6, mz * 0.91);
        } else {
            double friction = camera.isOnGround() ? 0.6 * 0.91 : 0.91;
            camera.setMotion(mx * friction, (my - 0.08) * 0.98, mz * friction);
        }
    }

    /** Keeps the camera within the bot's render distance (horizontal circle) and the world's height. */
    private void clamp(ClientPlayerEntity p) {
        double max = renderRadius();
        double dx = camera.getPosX() - p.getPosX();
        double dz = camera.getPosZ() - p.getPosZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        double x = camera.getPosX();
        double z = camera.getPosZ();
        if (d > max) {
            x = p.getPosX() + dx * max / d;
            z = p.getPosZ() + dz * max / d;
        }
        double y = MathHelper.clamp(camera.getPosY(), -64, 320);
        if (x != camera.getPosX() || y != camera.getPosY() || z != camera.getPosZ()) {
            camera.setPosition(x, y, z);
            camera.setMotion(Vector3d.ZERO);
        }
    }

    private double renderRadius() {
        return mc.gameSettings.renderDistanceChunks * 16;
    }

    private void handleClicks(GameSettings gs) {
        boolean left = false;
        boolean right = false;
        while (gs.keyBindAttack.isPressed()) {
            left = true;
        }
        while (gs.keyBindUseItem.isPressed()) {
            right = true;
        }
        // Never let a held click reach the bot's controller (it would dig/use at the camera's crosshair).
        gs.keyBindAttack.setPressed(false);
        gs.keyBindUseItem.setPressed(false);
        if (left) {
            select();
        } else if (right) {
            BetterBlockPos pos = new BetterBlockPos(camera.getPosX(), camera.getPosY(), camera.getPosZ());
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
            logDirect("Going to " + pos);
        }
    }

    private void select() {
        ClientPlayerEntity p = ctx.player();
        Vector3d start = camera.getEyePosition(1);
        Vector3d end = start.add(camera.getLookVec().scale(renderRadius() * 2));
        RayTraceResult block = p.world.rayTraceBlocks(new RayTraceContext(start, end, RayTraceContext.BlockMode.OUTLINE, RayTraceContext.FluidMode.NONE, camera));
        double blockDist = block.getType() == RayTraceResult.Type.MISS ? Double.MAX_VALUE : block.getHitVec().squareDistanceTo(start);

        Entity hit = null;
        double hitDist = blockDist;
        for (Entity e : ctx.entities()) {
            if (e == p || e == camera || !e.isAlive()) {
                continue;
            }
            AxisAlignedBB box = e.getBoundingBox().grow(e.getCollisionBorderSize() + 0.3);
            Optional<Vector3d> v = box.rayTrace(start, end);
            if (v.isPresent() && v.get().squareDistanceTo(start) < hitDist) {
                hitDist = v.get().squareDistanceTo(start);
                hit = e;
            }
        }
        if (hit != null) {
            Entity target = hit;
            baritone.getFollowProcess().follow(target::equals);
            logDirect("Following " + target.getName().getString());
            return;
        }
        if (block.getType() == RayTraceResult.Type.BLOCK) {
            BlockPos pos = ((BlockRayTraceResult) block).getPos();
            if (horizontalDistSq(pos, p) > renderRadius() * renderRadius()) {
                logDirect("That block is outside render distance");
                return;
            }
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(pos));
            logDirect("Going to block " + new BetterBlockPos(pos));
            return;
        }
        logDirect("Nothing selected");
    }

    private static double horizontalDistSq(BlockPos pos, Entity e) {
        double dx = pos.getX() + 0.5 - e.getPosX();
        double dz = pos.getZ() + 0.5 - e.getPosZ();
        return dx * dx + dz * dz;
    }

    private static final class Camera extends Entity {

        Camera(World world) {
            super(EntityType.PLAYER, world);
        }

        void syncPrev() {
            prevPosX = lastTickPosX = getPosX();
            prevPosY = lastTickPosY = getPosY();
            prevPosZ = lastTickPosZ = getPosZ();
            prevRotationYaw = rotationYaw;
            prevRotationPitch = rotationPitch;
        }

        @Override
        protected void registerData() {
        }

        @Override
        protected void readAdditional(CompoundNBT nbt) {
        }

        @Override
        protected void writeAdditional(CompoundNBT nbt) {
        }

        @Override
        public IPacket<?> createSpawnPacket() {
            throw new UnsupportedOperationException();
        }
    }
}
