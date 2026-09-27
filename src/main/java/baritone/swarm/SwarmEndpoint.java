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


package baritone.swarm;

import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.crypto.SigilCodec;
import baritone.swarm.crypto.SigilException;
import baritone.swarm.frame.SwarmChunker;
import baritone.swarm.frame.SwarmFrame;
import baritone.swarm.frame.SwarmFrameException;
import baritone.swarm.frame.SwarmMessage;
import baritone.swarm.frame.SwarmReassembler;
import baritone.swarm.frame.SwarmReject;
import baritone.swarm.frame.SwarmReplayGuard;
import baritone.swarm.transport.SwarmTransport;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * One swarm member: frames, seals, sends, and on the way in opens, checks and
 * reassembles. Every frame is sealed as exactly one S1C or S2C token ({@code swarmWireVersion}) under the circle
 * of the group it names; inbound frames must name the group whose circle opened
 * them. Rejections are counted, never thrown, on the receive path.
 */
public final class SwarmEndpoint implements Closeable {

    private final String self;
    private final SwarmConfig cfg;
    private final Map<String, SigilCircle> groups;
    private final List<SigilCircle> keyring;
    private final SwarmTransport transport;
    private final LongSupplier clockMs;
    private final SwarmChunker chunker;
    private final SwarmReplayGuard replay;
    private final SwarmReassembler reassembler;
    private final Map<SwarmReject, Long> rejects = new EnumMap<>(SwarmReject.class);
    private SwarmReject lastReject;

    /**
     * @param groups group id to circle; each group must have its own circle (distinct slug or name)
     * @param clockMs millisecond clock; the epoch is its value in seconds at construction
     */
    public SwarmEndpoint(String selfId, SwarmConfig cfg, Map<String, SigilCircle> groups, SwarmTransport transport,
                         LongSupplier clockMs) throws SwarmFrameException {
        if (!selfId.equals(transport.selfId())) {
            throw new IllegalArgumentException("transport id " + transport.selfId() + " != " + selfId);
        }
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("no groups: there is no plaintext mode");
        }
        Set<String> fingerprints = new HashSet<>();
        for (Map.Entry<String, SigilCircle> e : groups.entrySet()) {
            if (!SwarmFrame.isValidId(e.getKey())) {
                throw new IllegalArgumentException("bad group id");
            }
            if (!fingerprints.add(e.getValue().fingerprint())) {
                throw new IllegalArgumentException("groups must not share a circle");
            }
        }
        this.self = selfId;
        this.cfg = cfg;
        this.groups = Collections.unmodifiableMap(new LinkedHashMap<>(groups));
        this.keyring = new ArrayList<>(groups.values());
        this.transport = transport;
        this.clockMs = clockMs;
        this.chunker = new SwarmChunker(selfId, clockMs.getAsLong() / 1000L, cfg);
        this.replay = new SwarmReplayGuard(cfg);
        this.reassembler = new SwarmReassembler(cfg);
    }

    public String selfId() { return self; }
    public long epoch() { return chunker.epoch(); }
    public SwarmConfig config() { return cfg; }

    /**
     * Frame, seal and send one message.
     *
     * @param to member id or {@link SwarmFrame#BROADCAST}
     * @return the message id
     */
    public long send(String group, String to, String type, String body)
            throws SwarmFrameException, SigilException, IOException {
        SigilCircle circle = groups.get(group);
        if (circle == null) {
            throw new SwarmFrameException(SwarmReject.WRONG_GROUP, "no circle for group " + group);
        }
        List<SwarmFrame> frames = chunker.chunk(group, to, type, body, clockMs.getAsLong() / 1000L);
        List<String> lines = new ArrayList<>(frames.size());
        for (SwarmFrame f : frames) { // seal everything first so a failure sends nothing
            lines.add(SigilCodec.sealSingle(cfg.wire(), circle, f.encode(), cfg.sealLineBudget()));
        }
        for (String line : lines) {
            transport.send(to, line);
        }
        return frames.get(0).msgId();
    }

    /** Drain the transport, expire stale partial messages, and return completed messages. */
    public List<SwarmMessage> poll() throws IOException {
        List<SwarmMessage> out = new ArrayList<>();
        for (String line : transport.receive()) {
            SwarmMessage m = receiveLine(line);
            if (m != null) {
                out.add(m);
            }
        }
        int expired = reassembler.expire(clockMs.getAsLong());
        for (int i = 0; i < expired; i++) {
            count(SwarmReject.EXPIRED);
        }
        return out;
    }

    /** Process one inbound line. @return a completed message, or {@code null} (incomplete or rejected) */
    public SwarmMessage receiveLine(String line) {
        long now = clockMs.getAsLong();
        String token = line == null ? null : line.trim();
        if (!SwarmTransport.isSealed(token)) {
            return reject(SwarmReject.UNSEALED);
        }
        SigilCodec.Opened opened;
        String text;
        try {
            opened = SigilCodec.open(token, keyring);
            text = opened.text();
        } catch (SigilException e) {
            return reject(SwarmReject.UNSEALED);
        }
        if (opened.total() != 1) { // sigil's own multi-part fragments are never swarm frames
            return reject(SwarmReject.UNSEALED);
        }
        try {
            SwarmFrame f = SwarmFrame.decode(text);
            if (groups.get(f.group()) != opened.circle()) {
                throw new SwarmFrameException(SwarmReject.WRONG_GROUP, "group " + f.group() + " under another circle");
            }
            if (f.from().equals(self)) {
                throw new SwarmFrameException(SwarmReject.SELF, "own frame");
            }
            if (!f.to().equals(self) && !f.to().equals(SwarmFrame.BROADCAST)) {
                throw new SwarmFrameException(SwarmReject.NOT_FOR_ME, "for " + f.to());
            }
            replay.check(f, now);
            return reassembler.accept(f, now);
        } catch (SwarmFrameException e) {
            return reject(e.reason());
        }
    }

    private SwarmMessage reject(SwarmReject r) {
        count(r);
        return null;
    }

    private synchronized void count(SwarmReject r) {
        lastReject = r;
        rejects.merge(r, 1L, Long::sum);
    }

    public synchronized long rejectCount(SwarmReject r) {
        return rejects.getOrDefault(r, 0L);
    }

    public synchronized Map<SwarmReject, Long> rejectCounts() {
        return new EnumMap<>(rejects);
    }

    /** Most recent rejection reason, or {@code null}. */
    public synchronized SwarmReject lastReject() {
        return lastReject;
    }

    public int pendingMessages() {
        return reassembler.pendingCount();
    }

    @Override
    public void close() throws IOException {
        transport.close();
    }
}
