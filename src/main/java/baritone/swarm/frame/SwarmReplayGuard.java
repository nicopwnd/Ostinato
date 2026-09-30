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

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Sequence-based replay protection, per {@code (group, sender)}.
 *
 * <ul>
 *   <li>A frame from an epoch lower than the highest seen from that sender is refused
 *       ({@link SwarmReject#STALE_EPOCH}); a higher epoch (sender restarted) resets the window.
 *       Epochs are only compared with the same sender's own epochs, so no clock sync is needed.
 *       Caveat: if a sender's clock jumps backwards across a restart, peers refuse it until
 *       they forget it (restart) or its clock passes the old epoch.</li>
 *   <li>Within an epoch, each seq is accepted once, and seqs more than {@code swarmReplayWindow}
 *       below the highest seen are refused ({@link SwarmReject#REPLAY}). Out-of-order delivery
 *       inside the window is fine.</li>
 *   <li>Optional wall-clock check: when {@code swarmMaxClockSkewSec > 0}, frames whose {@code ts}
 *       differs from the local clock by more than that are refused ({@link SwarmReject#CLOCK_SKEW}).
 *       Off by default.</li>
 *   <li>At most {@code swarmMaxPeers} senders are tracked; new senders beyond that are refused
 *       ({@link SwarmReject#PEER_LIMIT}) rather than evicting state, which would reopen replays.</li>
 * </ul>
 */
public final class SwarmReplayGuard {

    private final int window;
    private final int maxSkewSec;
    private final int maxPeers;
    private final Map<String, Peer> peers = new HashMap<>();

    public SwarmReplayGuard(SwarmConfig cfg) {
        this.window = cfg.replayWindow();
        this.maxSkewSec = cfg.maxClockSkewSec();
        this.maxPeers = cfg.maxPeers();
    }

    private static final class Peer {
        long epoch;
        long highest;
        final TreeSet<Long> seen = new TreeSet<>();

        Peer(long epoch) {
            this.epoch = epoch;
        }
    }

    /** Accept (and record) the frame, or throw. */
    public synchronized void check(SwarmFrame f, long nowMs) throws SwarmFrameException {
        if (maxSkewSec > 0 && Math.abs(nowMs / 1000L - f.ts()) > maxSkewSec) {
            throw new SwarmFrameException(SwarmReject.CLOCK_SKEW, "ts off by " + (nowMs / 1000L - f.ts()) + "s");
        }
        String key = f.group() + '|' + f.from();
        Peer p = peers.get(key);
        if (p == null) {
            if (peers.size() >= maxPeers) {
                throw new SwarmFrameException(SwarmReject.PEER_LIMIT, peers.size() + " peers tracked");
            }
            p = new Peer(f.epoch());
            peers.put(key, p);
        }
        if (f.epoch() < p.epoch) {
            throw new SwarmFrameException(SwarmReject.STALE_EPOCH, "epoch " + f.epoch() + " < " + p.epoch);
        }
        if (f.epoch() > p.epoch) {
            p.epoch = f.epoch();
            p.highest = 0;
            p.seen.clear();
        }
        long seq = f.seq();
        if (seq <= p.highest - window) {
            throw new SwarmFrameException(SwarmReject.REPLAY, "seq " + seq + " below window");
        }
        if (!p.seen.add(seq)) {
            throw new SwarmFrameException(SwarmReject.REPLAY, "seq " + seq + " already seen");
        }
        if (seq > p.highest) {
            p.highest = seq;
            p.seen.headSet(p.highest - window, true).clear();
        }
    }

    public synchronized int peerCount() {
        return peers.size();
    }
}
