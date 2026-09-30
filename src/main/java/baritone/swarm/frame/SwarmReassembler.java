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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rebuilds messages from frames. Parts are keyed by
 * {@code (group, from, epoch, msgId)}; a part is only accepted into a pending
 * message when its {@code seq == msgId + i - 1} (checked by {@link SwarmFrame})
 * and its total, type and recipient match the first part seen. Order does not
 * matter; duplicates are dropped; incomplete messages expire after
 * {@code swarmReassemblyTimeoutMs}; at most {@code swarmMaxPendingMessages}
 * are held at once.
 */
public final class SwarmReassembler {

    private final long timeoutMs;
    private final int maxPending;
    private final int maxChunks;
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    public SwarmReassembler(SwarmConfig cfg) {
        this.timeoutMs = cfg.reassemblyTimeoutMs();
        this.maxPending = cfg.maxPendingMessages();
        this.maxChunks = cfg.maxChunks();
    }

    private static final class Pending {
        final int total;
        final String type;
        final String to;
        final long firstSeenMs;
        final String[] parts;
        int have;

        Pending(SwarmFrame f, long now) {
            this.total = f.total();
            this.type = f.type();
            this.to = f.to();
            this.firstSeenMs = now;
            this.parts = new String[f.total()];
        }
    }

    /** @return the completed message, or {@code null} while parts are still missing */
    public synchronized SwarmMessage accept(SwarmFrame f, long nowMs) throws SwarmFrameException {
        if (f.total() > maxChunks) {
            throw new SwarmFrameException(SwarmReject.TOO_LARGE, f.total() + " parts > swarmMaxChunks=" + maxChunks);
        }
        if (f.seq() - f.msgId() != f.index() - 1) { // also enforced by SwarmFrame; kept as a second gate
            throw new SwarmFrameException(SwarmReject.SPLICE, "seq does not belong to msg");
        }
        if (f.total() == 1) {
            return message(f, f.body());
        }
        String key = f.group() + '|' + f.from() + '|' + f.epoch() + '|' + f.msgId();
        Pending p = pending.get(key);
        if (p == null) {
            if (pending.size() >= maxPending) {
                throw new SwarmFrameException(SwarmReject.OVERFLOW, pending.size() + " messages pending");
            }
            p = new Pending(f, nowMs);
            pending.put(key, p);
        } else if (p.total != f.total() || !p.type.equals(f.type()) || !p.to.equals(f.to())) {
            throw new SwarmFrameException(SwarmReject.SPLICE, "part header disagrees with msg " + f.msgId());
        }
        int slot = f.index() - 1;
        if (p.parts[slot] != null) {
            throw new SwarmFrameException(SwarmReject.DUPLICATE, "part " + f.index() + " of msg " + f.msgId());
        }
        p.parts[slot] = f.body();
        if (++p.have < p.total) {
            return null;
        }
        pending.remove(key);
        StringBuilder b = new StringBuilder();
        for (String part : p.parts) {
            b.append(part);
        }
        return message(f, b.toString());
    }

    private static SwarmMessage message(SwarmFrame f, String body) {
        return new SwarmMessage(f.group(), f.from(), f.to(), f.epoch(), f.msgId(), f.type(), body);
    }

    /** Drop pending messages older than the timeout. @return how many were dropped */
    public synchronized int expire(long nowMs) {
        int n = 0;
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
            if (nowMs - it.next().firstSeenMs >= timeoutMs) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    public synchronized int pendingCount() {
        return pending.size();
    }
}
