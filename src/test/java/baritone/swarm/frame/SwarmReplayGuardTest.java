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


package baritone.swarm.frame;

import baritone.swarm.SwarmConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SwarmReplayGuardTest {

    private static SwarmFrame f(String from, long epoch, long seq, long ts) throws SwarmFrameException {
        return new SwarmFrame("g", from, "*", epoch, seq, ts, seq, 1, 1, "t", "");
    }

    private static void rejected(SwarmReplayGuard g, SwarmFrame f, long now, SwarmReject why) {
        try {
            g.check(f, now);
            fail("accepted " + f);
        } catch (SwarmFrameException e) {
            assertEquals(why, e.reason());
        }
    }

    @Test
    public void eachSeqOnceOutOfOrderInsideWindow() throws Exception {
        SwarmReplayGuard g = new SwarmReplayGuard(SwarmConfig.defaults());
        g.check(f("a", 10, 1, 0), 0);
        g.check(f("a", 10, 3, 0), 0);
        g.check(f("a", 10, 2, 0), 0); // late but new
        rejected(g, f("a", 10, 2, 0), 0, SwarmReject.REPLAY);
        rejected(g, f("a", 10, 3, 0), 0, SwarmReject.REPLAY);
        g.check(f("b", 10, 3, 0), 0); // seqs are per sender
    }

    @Test
    public void belowWindowIsRefused() throws Exception {
        SwarmConfig.Builder b = SwarmConfig.builder();
        b.replayWindow = 16;
        SwarmReplayGuard g = new SwarmReplayGuard(b.build());
        g.check(f("a", 10, 2, 0), 0);
        g.check(f("a", 10, 100, 0), 0);
        rejected(g, f("a", 10, 84, 0), 0, SwarmReject.REPLAY);  // 100 - 16
        rejected(g, f("a", 10, 3, 0), 0, SwarmReject.REPLAY);   // never seen, but too old
        g.check(f("a", 10, 85, 0), 0);                          // just inside
    }

    @Test
    public void epochsOnlyMoveForward() throws Exception {
        SwarmReplayGuard g = new SwarmReplayGuard(SwarmConfig.defaults());
        g.check(f("a", 10, 5, 0), 0);
        g.check(f("a", 11, 1, 0), 0); // restart: new epoch resets the window
        g.check(f("a", 11, 5, 0), 0);
        rejected(g, f("a", 10, 6, 0), 0, SwarmReject.STALE_EPOCH); // old epoch replayed
        rejected(g, f("a", 11, 1, 0), 0, SwarmReject.REPLAY);
    }

    @Test
    public void clockSkewIsOffByDefaultAndConfigurable() throws Exception {
        SwarmReplayGuard off = new SwarmReplayGuard(SwarmConfig.defaults());
        off.check(f("a", 10, 1, 0), 1_000_000_000L); // ts 0 vs now 1e6 s: fine, no NTP needed
        SwarmConfig.Builder b = SwarmConfig.builder();
        b.maxClockSkewSec = 30;
        SwarmReplayGuard on = new SwarmReplayGuard(b.build());
        on.check(f("a", 10, 1, 1000), 1_030_000L);
        on.check(f("a", 10, 2, 1000), 970_000L);
        rejected(on, f("a", 10, 3, 1000), 1_031_000L, SwarmReject.CLOCK_SKEW);
        rejected(on, f("a", 10, 4, 1000), 969_000L, SwarmReject.CLOCK_SKEW);
    }

    @Test
    public void peerLimitRefusesNewSendersWithoutForgetting() throws Exception {
        SwarmConfig.Builder b = SwarmConfig.builder();
        b.maxPeers = 2;
        SwarmReplayGuard g = new SwarmReplayGuard(b.build());
        g.check(f("a", 10, 1, 0), 0);
        g.check(f("b", 10, 1, 0), 0);
        rejected(g, f("c", 10, 1, 0), 0, SwarmReject.PEER_LIMIT);
        rejected(g, f("a", 10, 1, 0), 0, SwarmReject.REPLAY); // a's state was not evicted
        assertEquals(2, g.peerCount());
    }
}
