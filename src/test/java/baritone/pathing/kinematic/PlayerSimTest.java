package baritone.pathing.kinematic;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlayerSimTest {

    /** Floor at y=0 (blocks y=-1), plus an optional one-block wall at x=5. */
    private static PlayerSim.World flat(final boolean wall, final boolean step) {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++) {
                    for (int by = PlayerSim.floor(minY); by <= PlayerSim.floor(maxY); by++) {
                        for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++) {
                            boolean solid = by == -1 || (wall && bx == 5 && by <= 1) || (step && bx >= 5 && by == 0);
                            if (solid) out.add(new double[]{bx, by, bz, bx + 1, by + 1, bz + 1});
                        }
                    }
                }
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
    }

    private static PlayerSim start(PlayerSim.World w) {
        PlayerSim s = new PlayerSim(w);
        s.x = 0.5; s.z = 0.5; s.onGround = true;
        return s;
    }

    @Test
    public void sprintTopSpeed() {
        PlayerSim s = start(flat(false, false));
        for (int i = 0; i < 60; i++) s.tick(-90, true, true, false);
        double before = s.x;
        s.tick(-90, true, true, false);
        assertEquals(0.2806, s.x - before, 0.002); // vanilla sprint 5.612 m/s
        assertEquals(0.0, s.y, 1e-9);
    }

    @Test
    public void jumpApex() {
        PlayerSim s = start(flat(false, false));
        double apex = 0;
        for (int i = 0; i < 20; i++) {
            s.tick(0, false, false, i == 0);
            apex = Math.max(apex, s.y);
        }
        assertEquals(1.2522, apex, 0.001);
        assertTrue(s.onGround);
    }

    @Test
    public void wallStopsAndStepClimbsSlab() {
        PlayerSim w = start(flat(true, false));
        for (int i = 0; i < 40; i++) w.tick(-90, true, true, false);
        assertEquals(4.7, w.x, 1e-6);
        PlayerSim s = start(flat(false, true));
        for (int i = 0; i < 40; i++) s.tick(-90, true, false, false);
        assertTrue(s.x < 5.0); // a full block is higher than the 0.6 step
    }
}
