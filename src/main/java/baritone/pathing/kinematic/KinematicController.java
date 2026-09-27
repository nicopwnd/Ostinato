package baritone.pathing.kinematic;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.BlockStateInterface;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.vector.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Drives plain walking stretches of a Baritone path with physics look-ahead instead of the per-movement
 * state machines: each tick it simulates a handful of yaw/jump choices a few ticks ahead with
 * {@link PlayerSim} and presses the keys of the one that gets furthest along the path while staying on it.
 * Anything it can't model (breaking, placing, water, big drops, parkour...) is left to Baritone.
 */
public final class KinematicController {

    private static final int MAX_LOOKAHEAD_MOVES = 12;
    private static final int HORIZON = 12;
    private static final float[] YAW_OFFSETS = {0, -8, 8, -20, 20, -40, 40};
    private static final double CORRIDOR = 0.55;
    /** Hand back to Baritone this far before the end of the drivable stretch. */
    private static final double HANDBACK = 1.2;

    private final IPlayerContext ctx;
    private final CachedWorld world = new CachedWorld();
    private final PlayerSim real;
    private final PlayerSim sim;
    private final List<double[]> line = new ArrayList<>(); // x, y, z, arc length
    private int lastMove;

    public KinematicController(IPlayerContext ctx) {
        this.ctx = ctx;
        this.real = new PlayerSim(world);
        this.sim = new PlayerSim(world);
    }

    /**
     * @return the path position to continue from if the controller drove this tick, or -1 to let Baritone run the movement
     */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        if (!Baritone.settings().kinematicTravel.value || ctx.player().isInWater() || ctx.player().isInLava()
                || ctx.player().isOnLadder() || ctx.player().isElytraFlying()) {
            return -1;
        }
        world.reset();
        if (!buildLine(path, pathPosition)) {
            return -1;
        }
        Vector3d p = ctx.player().getPositionVec();
        Vector3d m = ctx.player().getMotion();
        real.x = p.x; real.y = p.y; real.z = p.z;
        real.vx = m.x; real.vy = m.y; real.vz = m.z;
        real.onGround = ctx.player().isOnGround();
        real.sprinting = ctx.player().isSprinting();
        real.collidedH = ctx.player().collidedHorizontally;

        double[] here = project(real.x, real.z);
        double end = line.get(line.size() - 1)[3];
        if (here[1] > CORRIDOR + 0.35 || end - here[0] < HANDBACK) {
            return -1;
        }
        int newPos = syncPosition(path, pathPosition);

        float best = Float.NaN;
        boolean bestJump = false;
        double bestScore = -1e9;
        for (int j = 0; j < (real.onGround ? 2 : 1); j++) {
            for (float off : YAW_OFFSETS) {
                double score = rollout(off, j == 1, here[0]);
                if (score > bestScore + 1e-6) {
                    bestScore = score;
                    best = off;
                    bestJump = j == 1;
                }
            }
        }
        if (bestScore <= here[0] + 0.05) {
            return -1; // nothing makes progress safely; Baritone knows how to recover
        }
        float yaw = aim(real.x, real.z, here[0]) + best;
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, 0), false);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, bestJump);
        return newPos;
    }

    /** Score = arc progress at the end of the horizon; -inf if the player leaves the corridor or drops below the path. */
    private double rollout(float yawOffset, boolean jump, double s0) {
        sim.copyFrom(real);
        double s = s0;
        for (int t = 0; t < HORIZON; t++) {
            float off = t < 4 ? yawOffset : 0;
            sim.tick(aim(sim.x, sim.z, s) + off, true, true, jump && t == 0);
            double[] pr = project(sim.x, sim.z);
            if (pr[1] > CORRIDOR) {
                return -1e9;
            }
            s = Math.max(s, pr[0]);
            if (sim.y < floorAt(pr[0]) - 0.4) {
                return -1e9;
            }
            if (s >= line.get(line.size() - 1)[3] - 0.3) {
                return s + (HORIZON - t) * 0.3; // reached the end early
            }
        }
        // keep a little credit for speed along the path so it prefers carrying momentum
        return s + 0.5 * Math.sqrt(sim.vx * sim.vx + sim.vz * sim.vz);
    }

    private float aim(double x, double z, double s) {
        double[] tgt = pointAt(s + 1.6);
        return (float) Math.toDegrees(Math.atan2(-(tgt[0] - x), tgt[2] - z));
    }

    private boolean buildLine(IPath path, int pathPosition) {
        line.clear();
        List<IMovement> moves = path.movements();
        if (pathPosition >= moves.size()) {
            return false;
        }
        BlockStateInterface bsi = null;
        BetterBlockPos src = moves.get(pathPosition).getSrc();
        add(src);
        int i = pathPosition;
        for (; i < moves.size() && i < pathPosition + MAX_LOOKAHEAD_MOVES; i++) {
            IMovement mv = moves.get(i);
            if (!drivable(mv)) {
                break;
            }
            Movement movement = (Movement) mv;
            if (movement.toBreakCached == null || movement.toPlaceCached == null) {
                if (bsi == null) {
                    bsi = new BlockStateInterface(ctx);
                }
                movement.toBreak(bsi);
                movement.toPlace(bsi);
            }
            if (!movement.toBreakCached.isEmpty() || !movement.toPlaceCached.isEmpty()) {
                break;
            }
            add(mv.getDest());
        }
        lastMove = i - 1;
        return line.size() >= 3;
    }

    private static boolean drivable(IMovement mv) {
        if (mv instanceof MovementTraverse || mv instanceof MovementDiagonal || mv instanceof MovementAscend) {
            return mv.getDest().y - mv.getSrc().y <= 1;
        }
        return mv instanceof MovementDescend && mv.getSrc().y - mv.getDest().y == 1;
    }

    private void add(BetterBlockPos b) {
        double x = b.x + 0.5, z = b.z + 0.5;
        double s = 0;
        if (!line.isEmpty()) {
            double[] prev = line.get(line.size() - 1);
            s = prev[3] + Math.sqrt((x - prev[0]) * (x - prev[0]) + (z - prev[2]) * (z - prev[2]));
        }
        line.add(new double[]{x, b.y, z, s});
    }

    /** Nearest point on the polyline: {arc length, horizontal distance}. */
    private double[] project(double x, double z) {
        double bestS = 0, bestD = Double.MAX_VALUE;
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = line.get(i), b = line.get(i + 1);
            double dx = b[0] - a[0], dz = b[2] - a[2];
            double len2 = dx * dx + dz * dz;
            double t = len2 == 0 ? 0 : ((x - a[0]) * dx + (z - a[2]) * dz) / len2;
            t = Math.max(0, Math.min(1, t));
            double px = a[0] + dx * t, pz = a[2] + dz * t;
            double d = (x - px) * (x - px) + (z - pz) * (z - pz);
            if (d < bestD) {
                bestD = d;
                bestS = a[3] + (b[3] - a[3]) * t;
            }
        }
        return new double[]{bestS, Math.sqrt(bestD)};
    }

    private double[] pointAt(double s) {
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = line.get(i), b = line.get(i + 1);
            if (s <= b[3] || i + 2 == line.size()) {
                double len = b[3] - a[3];
                double t = len == 0 ? 1 : Math.max(0, Math.min(1, (s - a[3]) / len));
                return new double[]{a[0] + (b[0] - a[0]) * t, a[1], a[2] + (b[2] - a[2]) * t};
            }
        }
        return line.get(line.size() - 1);
    }

    /** Lowest floor the player may be at around arc length s (an ascend/descend switches floors mid-segment). */
    private double floorAt(double s) {
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = line.get(i), b = line.get(i + 1);
            if (s <= b[3] || i + 2 == line.size()) {
                return Math.min(a[1], b[1]);
            }
        }
        return line.get(line.size() - 1)[1];
    }

    /** Advance past moves whose destination the player already stands in (at floor height, so mid-jump counts). */
    private int syncPosition(IPath path, int pathPosition) {
        int fx = PlayerSim.floor(real.x), fz = PlayerSim.floor(real.z);
        int fy = PlayerSim.floor(real.y + 1e-3);
        for (int i = lastMove; i >= pathPosition; i--) {
            BetterBlockPos d = path.movements().get(i).getDest();
            if (d.x == fx && d.z == fz && fy >= d.y - 1 && fy <= d.y + 1) {
                return i + 1;
            }
        }
        return pathPosition;
    }

    /** Collision boxes and slipperiness read straight from the client world, memoised for one tick. */
    private final class CachedWorld implements PlayerSim.World {
        private final Long2ObjectOpenHashMap<List<double[]>> cache = new Long2ObjectOpenHashMap<>();
        private final BlockPos.Mutable pos = new BlockPos.Mutable();

        void reset() {
            cache.clear();
        }

        @Override
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++) {
                for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++) { // -1: fences stick up 1.5
                    for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++) {
                        out.addAll(boxes(x, y, z));
                    }
                }
            }
        }

        private List<double[]> boxes(int x, int y, int z) {
            long key = BlockPos.pack(x, y, z);
            List<double[]> got = cache.get(key);
            if (got != null) {
                return got;
            }
            pos.setPos(x, y, z);
            BlockState state = ctx.world().getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(ctx.world(), pos);
            if (shape.isEmpty()) {
                got = Collections.emptyList();
            } else {
                got = new ArrayList<>();
                for (AxisAlignedBB bb : shape.toBoundingBoxList()) {
                    got.add(new double[]{bb.minX + x, bb.minY + y, bb.minZ + z, bb.maxX + x, bb.maxY + y, bb.maxZ + z});
                }
            }
            cache.put(key, got);
            return got;
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            pos.setPos(x, y, z);
            return ctx.world().getBlockState(pos).getBlock().getSlipperiness();
        }
    }
}
