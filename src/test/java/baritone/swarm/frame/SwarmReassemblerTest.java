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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SwarmReassemblerTest {

    private static final SwarmConfig CFG = unsigned(); // chunking only; the S2S budget is covered in SwarmConfigTest

    private static SwarmConfig unsigned() {
        SwarmConfig.Builder b = SwarmConfig.builder();
        b.requireSignedSender = false;
        return b.build();
    }

    static String body(int chars) {
        StringBuilder b = new StringBuilder();
        String alphabet = "abc|\u00e9\u4e16\ud83d\ude00 xyz0123456789";
        for (int i = 0; b.length() < chars; i++) {
            b.appendCodePoint(alphabet.codePointAt(alphabet.offsetByCodePoints(0, i % alphabet.codePointCount(0, alphabet.length()))));
        }
        return b.toString();
    }

    @Test
    public void chunkerFitsBudgetAndBindsSeqToMsg() throws Exception {
        SwarmChunker c = new SwarmChunker("bot1", 100, CFG);
        String text = body(500);
        List<SwarmFrame> frames = c.chunk("g", "bot2", "task", text, 5);
        assertTrue(frames.size() > 1 && frames.size() <= CFG.maxChunks());
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < frames.size(); i++) {
            SwarmFrame f = frames.get(i);
            assertTrue(f.encodedBytes() <= CFG.maxFrameBytes());
            assertEquals(1, f.msgId());
            assertEquals(i + 1, f.seq());
            assertEquals(i + 1, f.index());
            assertEquals(frames.size(), f.total());
            joined.append(f.body());
        }
        assertEquals(text, joined.toString());
        // next message continues the sequence
        List<SwarmFrame> next = c.chunk("g", "bot2", "ping", "", 5);
        assertEquals(1, next.size());
        assertEquals(frames.size() + 1, next.get(0).msgId());
    }

    @Test
    public void chunkerRefusesOversizedMessageWithoutBurningSeq() throws Exception {
        SwarmChunker c = new SwarmChunker("bot1", 100, CFG);
        try {
            c.chunk("g", "*", "t", body(5000), 5);
            fail();
        } catch (SwarmFrameException e) {
            assertEquals(SwarmReject.TOO_LARGE, e.reason());
        }
        assertEquals(1, c.nextSeq());
    }

    @Test
    public void reassemblesOutOfOrderWithDuplicates() throws Exception {
        SwarmChunker c = new SwarmChunker("bot1", 100, CFG);
        String text = body(600);
        List<SwarmFrame> frames = new ArrayList<>(c.chunk("g", "*", "task", text, 5));
        assertTrue(frames.size() >= 4);
        Collections.shuffle(frames, new Random(7));
        Collections.reverse(frames);
        SwarmReassembler r = new SwarmReassembler(CFG);
        SwarmMessage done = null;
        int dups = 0;
        for (int i = 0; i < frames.size(); i++) {
            SwarmMessage m = r.accept(frames.get(i), 0);
            if (i < frames.size() - 1) {
                assertNull(m);
                try {
                    r.accept(frames.get(i), 0); // duplicate of a part already held
                    fail();
                } catch (SwarmFrameException e) {
                    assertEquals(SwarmReject.DUPLICATE, e.reason());
                    dups++;
                }
            } else {
                done = m;
            }
        }
        assertNotNull(done);
        assertEquals(frames.size() - 1, dups);
        assertEquals(text, done.body());
        assertEquals("bot1", done.from());
        assertEquals("task", done.type());
        assertEquals(1, done.msgId());
        assertEquals(0, r.pendingCount());
    }

    @Test
    public void interleavedMessagesStaySeparate() throws Exception {
        SwarmChunker c = new SwarmChunker("bot1", 100, CFG);
        String a = body(400);
        String b = body(300).toUpperCase();
        List<SwarmFrame> fa = c.chunk("g", "*", "a", a, 5);
        List<SwarmFrame> fb = c.chunk("g", "*", "b", b, 5);
        SwarmReassembler r = new SwarmReassembler(CFG);
        List<SwarmMessage> out = new ArrayList<>();
        for (int i = 0; i < Math.max(fa.size(), fb.size()); i++) {
            if (i < fb.size()) {
                add(out, r.accept(fb.get(fb.size() - 1 - i), 0));
            }
            if (i < fa.size()) {
                add(out, r.accept(fa.get(i), 0));
            }
        }
        assertEquals(2, out.size());
        for (SwarmMessage m : out) {
            assertEquals(m.type().equals("a") ? a : b, m.body());
        }
    }

    private static void add(List<SwarmMessage> out, SwarmMessage m) {
        if (m != null) {
            out.add(m);
        }
    }

    @Test
    public void missingPartExpires() throws Exception {
        SwarmChunker c = new SwarmChunker("bot1", 100, CFG);
        List<SwarmFrame> frames = c.chunk("g", "*", "t", body(400), 5);
        SwarmReassembler r = new SwarmReassembler(CFG);
        for (int i = 0; i < frames.size() - 1; i++) { // last part never arrives
            assertNull(r.accept(frames.get(i), 1000));
        }
        assertEquals(1, r.pendingCount());
        assertEquals(0, r.expire(1000 + CFG.reassemblyTimeoutMs() - 1));
        assertEquals(1, r.expire(1000 + CFG.reassemblyTimeoutMs()));
        assertEquals(0, r.pendingCount());
        // the late part alone no longer completes anything
        assertNull(r.accept(frames.get(frames.size() - 1), 1000 + CFG.reassemblyTimeoutMs()));
    }

    @Test
    public void spliceAcrossMessagesIsRefused() throws Exception {
        SwarmChunker c = new SwarmChunker("bot1", 100, CFG);
        List<SwarmFrame> m1 = c.chunk("g", "*", "t", body(300), 5); // msg 1
        List<SwarmFrame> m2 = c.chunk("g", "*", "t", body(300).toUpperCase(), 5); // msg 1+m1.size()
        assertTrue(m1.size() >= 2 && m2.size() >= 2);
        SwarmFrame p2of1 = m1.get(1);
        long msg2 = m2.get(0).msgId();

        // Relabel part 2 of msg 1 as part 2 of msg 2 but keep its seq: seq/msg mismatch.
        try {
            new SwarmFrame("g", "bot1", "*", 100, p2of1.seq(), 5, msg2, 2, p2of1.total(), "t", p2of1.body());
            fail();
        } catch (SwarmFrameException e) {
            assertEquals(SwarmReject.SPLICE, e.reason());
        }

        // Same message id and consistent seq, but a different total/type/recipient: refused.
        SwarmReassembler r = new SwarmReassembler(CFG);
        assertNull(r.accept(m2.get(0), 0));
        SwarmFrame forgedTotal = new SwarmFrame("g", "bot1", "*", 100, msg2 + 1, 5, msg2, 2, m2.size() + 1, "t", "evil");
        SwarmFrame forgedType = new SwarmFrame("g", "bot1", "*", 100, msg2 + 1, 5, msg2, 2, m2.size(), "x", "evil");
        SwarmFrame forgedTo = new SwarmFrame("g", "bot1", "bot9", 100, msg2 + 1, 5, msg2, 2, m2.size(), "t", "evil");
        for (SwarmFrame f : new SwarmFrame[]{forgedTotal, forgedType, forgedTo}) {
            try {
                r.accept(f, 0);
                fail(f.toString());
            } catch (SwarmFrameException e) {
                assertEquals(SwarmReject.SPLICE, e.reason());
            }
        }

        // Parts from another sender or epoch with the same msg id never join this message.
        SwarmFrame otherSender = new SwarmFrame("g", "bot7", "*", 100, msg2 + 1, 5, msg2, 2, m2.size(), "t", "evil");
        SwarmFrame otherEpoch = new SwarmFrame("g", "bot1", "*", 101, msg2 + 1, 5, msg2, 2, m2.size(), "t", "evil");
        assertNull(r.accept(otherSender, 0));
        assertNull(r.accept(otherEpoch, 0));
        assertEquals(3, r.pendingCount());

        // The genuine message still completes with its own parts only.
        SwarmMessage done = null;
        for (int i = 1; i < m2.size(); i++) {
            done = r.accept(m2.get(i), 0);
        }
        assertNotNull(done);
        assertEquals(body(300).toUpperCase(), done.body());
    }

    @Test
    public void pendingLimitAndChunkLimit() throws Exception {
        SwarmConfig.Builder b = SwarmConfig.builder();
        b.maxPendingMessages = 2;
        SwarmConfig cfg = b.build();
        SwarmReassembler r = new SwarmReassembler(cfg);
        assertNull(r.accept(new SwarmFrame("g", "a", "*", 1, 1, 0, 1, 1, 2, "t", "x"), 0));
        assertNull(r.accept(new SwarmFrame("g", "a", "*", 1, 3, 0, 3, 1, 2, "t", "x"), 0));
        try {
            r.accept(new SwarmFrame("g", "a", "*", 1, 5, 0, 5, 1, 2, "t", "x"), 0);
            fail();
        } catch (SwarmFrameException e) {
            assertEquals(SwarmReject.OVERFLOW, e.reason());
        }
        try {
            r.accept(new SwarmFrame("g", "a", "*", 1, 7, 0, 7, 1, cfg.maxChunks() + 1, "t", "x"), 0);
            fail();
        } catch (SwarmFrameException e) {
            assertEquals(SwarmReject.TOO_LARGE, e.reason());
        }
    }
}
