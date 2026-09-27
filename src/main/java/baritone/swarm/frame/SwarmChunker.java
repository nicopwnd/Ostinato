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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits one outbound message into frames that each fit a single S1C token.
 * Owns the sender's sequence counter for one epoch: every frame gets the next
 * seq, and the message id is the seq of its first frame. Bodies are split on
 * UTF-8 code-point boundaries. Not related to sigil's own {@code i/n} parts,
 * which the swarm never uses.
 */
public final class SwarmChunker {

    private final String self;
    private final long epoch;
    private final int maxFrameBytes;
    private final int maxChunks;
    private long nextSeq = 1;

    public SwarmChunker(String self, long epoch, SwarmConfig cfg) throws SwarmFrameException {
        if (!SwarmFrame.isValidId(self)) {
            throw new SwarmFrameException(SwarmReject.MALFORMED, "bad self id");
        }
        this.self = self;
        this.epoch = epoch;
        this.maxFrameBytes = cfg.maxFrameBytes();
        this.maxChunks = Math.min(cfg.maxChunks(), SwarmFrame.MAX_TOTAL);
    }

    public long epoch() {
        return epoch;
    }

    /** Seq the next frame will carry. */
    public synchronized long nextSeq() {
        return nextSeq;
    }

    public synchronized List<SwarmFrame> chunk(String group, String to, String type, String body, long tsSec)
            throws SwarmFrameException {
        long msgId = nextSeq;
        // Worst-case header for this message: widest seq and i/n it could carry.
        String header = SwarmFrame.header(group, self, to, epoch, msgId + maxChunks - 1, tsSec, msgId,
                maxChunks, maxChunks, type);
        int budget = maxFrameBytes - header.getBytes(StandardCharsets.UTF_8).length;
        if (budget < 8) {
            throw new SwarmFrameException(SwarmReject.TOO_LARGE, "ids too long for a " + maxFrameBytes + "-byte frame");
        }
        List<String> pieces = split(body, budget);
        if (pieces.size() > maxChunks) {
            throw new SwarmFrameException(SwarmReject.TOO_LARGE,
                    "needs " + pieces.size() + " frames, swarmMaxChunks=" + maxChunks);
        }
        int n = pieces.size();
        List<SwarmFrame> frames = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            frames.add(new SwarmFrame(group, self, to, epoch, msgId + i, tsSec, msgId, i + 1, n, type, pieces.get(i)));
        }
        nextSeq = msgId + n;
        return frames;
    }

    static List<String> split(String body, int budget) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curBytes = 0;
        for (int i = 0; i < body.length(); ) {
            int cp = body.codePointAt(i);
            int len = Character.charCount(cp);
            int bytes = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (curBytes > 0 && curBytes + bytes > budget) {
                out.add(cur.toString());
                cur.setLength(0);
                curBytes = 0;
            }
            cur.append(body, i, i + len);
            curBytes += bytes;
            i += len;
        }
        if (curBytes > 0 || out.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }
}
