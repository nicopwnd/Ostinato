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


package baritone.swarm.crypto;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SigilS2CTest {

    private static SigilCircle circle;
    private static SigilCircle other;
    private static List<SigilCircle> ring;

    @BeforeClass
    public static void derive() throws Exception {
        circle = SigilCircle.derive("s2-java-test", "public-s2-test-passphrase-DO-NOT-USE");
        other = SigilCircle.derive("s2-java-test", "a-different-public-test-passphrase");
        ring = Collections.singletonList(circle);
    }

    private static String text(int chars) {
        StringBuilder b = new StringBuilder();
        String alphabet = "portal at 1847 12 -320 \u00e9\u4e16\ud83d\ude00|";
        for (int i = 0; b.length() < chars; i++) {
            b.appendCodePoint(alphabet.codePointAt(alphabet.offsetByCodePoints(0,
                    i % alphabet.codePointCount(0, alphabet.length()))));
        }
        return b.toString();
    }

    private static String roundTrip(List<String> lines, String expectedSender) throws Exception {
        List<SigilS2C.Opened> opened = new ArrayList<>();
        for (String l : lines) {
            assertTrue(l, l.startsWith("S2C." + circle.slug() + "."));
            opened.add(SigilS2C.open(l, ring));
        }
        List<SigilS2C.Message> msgs = SigilS2C.assemble(opened);
        assertEquals(1, msgs.size());
        assertTrue(msgs.get(0).complete());
        assertEquals(expectedSender, msgs.get(0).sender());
        return msgs.get(0).text();
    }

    private static String edit(String token, int offset, int xor) throws Exception {
        String head = token.substring(0, token.lastIndexOf('.') + 1);
        byte[] data = SigilB64.decode(token.substring(head.length()));
        data[offset] ^= (byte) xor;
        return head + SigilB64.encode(data);
    }

    private static void mustFail(String line) {
        try {
            SigilS2C.Opened o = SigilS2C.open(line, ring);
            fail("opened: " + o.text());
        } catch (SigilException expected) {
        }
    }

    @Test
    public void capacityMatchesSigilReadme() {
        assertEquals(156, SigilS2C.payloadRoom(256, false));
        assertEquals(149, SigilS2C.payloadRoom(256, true));
        assertEquals(139, SigilS2C.payloadRoom(234, false));
        assertEquals(132, SigilS2C.payloadRoom(234, true));
        assertEquals(139, SigilCodec.maxSingleLinePayloadBytes(SigilWire.S2, 234));
        assertEquals(135, SigilCodec.maxSingleLinePayloadBytes(SigilWire.S1, 234));
    }

    @Test
    public void roundTripSingleMultiUnicodeSenderEmpty() throws Exception {
        for (int chars : Arrays.asList(0, 1, 156, 157, 400, 900)) {
            String t = text(chars);
            for (int maxLine : Arrays.asList(256, 234, 180)) {
                List<String> lines = SigilS2C.seal(circle, t, "", maxLine);
                for (String l : lines) {
                    assertTrue(l.length() <= maxLine);
                }
                assertEquals(t, roundTrip(lines, null));
                List<String> withSender = SigilS2C.seal(circle, t, "Steve_\u00e9", maxLine);
                assertEquals(t, roundTrip(withSender, "Steve_\u00e9"));
            }
        }
        // exactly-full single line vs first split
        assertEquals(1, SigilS2C.seal(circle, repeat('a', 156), "", 256).size());
        assertEquals(2, SigilS2C.seal(circle, repeat('a', 157), "", 256).size());
        assertEquals(repeat('a', 139), SigilS2C.open(SigilS2C.sealSingle(circle, repeat('a', 139), 234), ring).text());
        try {
            SigilS2C.sealSingle(circle, repeat('a', 140), 234);
            fail();
        } catch (SigilException expected) {
        }
    }

    @Test
    public void tooManyPartsAndLongSender() {
        try {
            SigilS2C.seal(circle, repeat('x', 149 * 16 + 1), "", 256);
            fail();
        } catch (SigilException e) {
            assertTrue(e.getMessage().contains("at most 16"));
        }
        try {
            SigilS2C.seal(circle, "hi", repeat('s', 33), 256);
            fail();
        } catch (SigilException expected) {
        }
    }

    @Test
    public void spliceAcrossMessagesNeverAssembles() throws Exception {
        List<String> a = SigilS2C.seal(circle, repeat('a', 400), "", 256);
        List<String> b = SigilS2C.seal(circle, repeat('b', 400), "", 256);
        assertEquals(3, a.size());
        assertEquals(3, b.size());
        List<SigilS2C.Opened> mixed = new ArrayList<>();
        mixed.add(SigilS2C.open(a.get(0), ring));
        mixed.add(SigilS2C.open(b.get(1), ring));
        mixed.add(SigilS2C.open(a.get(2), ring));
        for (SigilS2C.Message m : SigilS2C.assemble(mixed)) {
            assertFalse(m.complete());
            assertNull(m.text());
        }
        // Rewriting B's part 2 to carry A's message id breaks the tag (the id is in the AAD).
        byte[] midA = SigilS2C.open(a.get(0), ring).mid();
        String head = b.get(1).substring(0, b.get(1).lastIndexOf('.') + 1);
        byte[] data = SigilB64.decode(b.get(1).substring(head.length()));
        System.arraycopy(midA, 0, data, 2, SigilS2C.MID_LEN);
        mustFail(head + SigilB64.encode(data));
        // Duplicates are ignored, not joined twice.
        List<SigilS2C.Opened> dup = new ArrayList<>();
        for (String l : Arrays.asList(a.get(0), a.get(1), a.get(1), a.get(2), a.get(0))) {
            dup.add(SigilS2C.open(l, ring));
        }
        assertEquals(repeat('a', 400), SigilS2C.assemble(dup).get(0).text());
    }

    @Test
    public void senderTamperFails() throws Exception {
        String line = SigilS2C.seal(circle, "stash at 1 2 3", "Steve", 256).get(0);
        assertEquals("Steve", SigilS2C.open(line, ring).sender());
        mustFail(edit(line, 1 + 12 + 1, 0x01));  // first sender byte, inside the ciphertext
        mustFail(edit(line, 0, SigilS2C.FLAG_S)); // S cleared: header is in the AAD
        String noSender = SigilS2C.seal(circle, "stash at 1 2 3", "", 256).get(0);
        mustFail(edit(noSender, 0, SigilS2C.FLAG_S)); // S set
    }

    @Test
    public void relabelledPartsFail() throws Exception {
        List<String> lines = SigilS2C.seal(circle, repeat('r', 400), "", 256);
        assertEquals(3, lines.size());
        mustFail(edit(lines.get(0), 1, 0x10));  // 1/3 -> 2/3
        mustFail(edit(lines.get(0), 1, 0x01));  // 1/3 -> 1/2
        mustFail(edit(lines.get(1), 0, SigilS2C.FLAG_Z)); // Z added
        mustFail(edit(lines.get(0), 0, 0x10));  // reserved bit
        // multi-part rewritten as single: drop M, part byte and id
        String head = lines.get(0).substring(0, lines.get(0).lastIndexOf('.') + 1);
        byte[] data = SigilB64.decode(lines.get(0).substring(head.length()));
        byte[] single = new byte[data.length - 7];
        single[0] = (byte) (data[0] & ~SigilS2C.FLAG_M);
        System.arraycopy(data, 8, single, 1, data.length - 8);
        mustFail(head + SigilB64.encode(single));
        mustFail(SigilS2C.seal(circle, "x", "", 256).get(0).replace("S2C." + circle.slug(), "S2C.zzzz"));
        try {
            SigilS2C.open(SigilS2C.seal(circle, "x", "", 256).get(0), Collections.singletonList(other));
            fail("wrong passphrase");
        } catch (SigilException expected) {
        }
    }

    /** Frames whose tag is valid but break the header/body rules must still be refused. */
    @Test
    public void strictValidationEvenWithValidTag() throws Exception {
        byte[] nonce = new byte[12];
        byte[] mid = {1, 2, 3, 4, 5, 6};
        byte[] body = "hello".getBytes("UTF-8");
        // Z set on a raw body (does not start with 0xC2)
        mustFail(SigilS2C.sealFrame(circle, new byte[]{SigilS2C.FLAG_Z}, body, nonce));
        // J on a single-line frame / on the last part
        mustFail(SigilS2C.sealFrame(circle, new byte[]{SigilS2C.FLAG_J}, body, nonce));
        byte[] last = {(byte) (SigilS2C.FLAG_M | SigilS2C.FLAG_J), 0x11, 1, 2, 3, 4, 5, 6};
        mustFail(SigilS2C.sealFrame(circle, last, body, nonce));
        // S on part 2
        byte[] s2 = {(byte) (SigilS2C.FLAG_M | SigilS2C.FLAG_S), 0x11, 1, 2, 3, 4, 5, 6};
        mustFail(SigilS2C.sealFrame(circle, s2, new byte[]{1, 'x', 'y'}, nonce));
        // reserved bits, M with n < 2, i > n
        mustFail(SigilS2C.sealFrame(circle, new byte[]{(byte) 0x80}, body, nonce));
        mustFail(SigilS2C.sealFrame(circle, new byte[]{SigilS2C.FLAG_M, 0x00, 1, 2, 3, 4, 5, 6}, body, nonce));
        mustFail(SigilS2C.sealFrame(circle, new byte[]{SigilS2C.FLAG_M, 0x21, 1, 2, 3, 4, 5, 6}, body, nonce));
        // bad sender length, invalid UTF-8
        mustFail(SigilS2C.sealFrame(circle, new byte[]{SigilS2C.FLAG_S}, new byte[]{9, 'a'}, nonce));
        mustFail(SigilS2C.sealFrame(circle, new byte[]{SigilS2C.FLAG_S}, new byte[]{0}, nonce));
        mustFail(SigilS2C.sealFrame(circle, new byte[]{0}, new byte[]{(byte) 0xFF, 'a'}, nonce));
        // control: a well-formed frame with the same machinery opens
        assertEquals("hello", SigilS2C.open(SigilS2C.sealFrame(circle, new byte[]{0}, body, nonce), ring).text());
        byte[] ok = {(byte) (SigilS2C.FLAG_M | SigilS2C.FLAG_J), 0x01, 1, 2, 3, 4, 5, 6};
        assertTrue(SigilS2C.open(SigilS2C.sealFrame(circle, ok, body, nonce), ring).join());
        assertEquals(Arrays.toString(mid), Arrays.toString(SigilS2C.open(SigilS2C.sealFrame(circle, ok, body, nonce), ring).mid()));
    }

    @Test
    public void s1StillOpensAndWiresStaySeparate() throws Exception {
        String s1 = SigilS1C.sealSingle(circle, "old wire", 256);
        SigilCodec.Opened o = SigilCodec.open("[chat] " + s1, ring);
        assertEquals(SigilWire.S1, o.wire());
        assertEquals("old wire", o.text());
        String s2 = SigilCodec.sealSingle(SigilWire.S2, circle, "new wire", 256);
        assertEquals(SigilWire.S2, SigilCodec.open(s2, ring).wire());
        assertEquals("new wire", SigilCodec.open(s2, ring).text());
        // An S1 blob relabelled S2 (and vice versa) does not open: different AAD domains.
        mustFail("S2" + s1.substring(2));
        try {
            SigilCodec.open("S1" + s2.substring(2), ring);
            fail();
        } catch (SigilException expected) {
        }
        assertEquals(SigilWire.S2, SigilWire.parse("s2"));
        assertEquals(SigilWire.S1, SigilWire.parse(" S1 "));
        assertEquals(SigilWire.S2, SigilWire.parse("S9"));
        assertEquals(SigilWire.S2, SigilWire.parse(null));
    }

    @Test
    public void signetModesAreRecognisedButNotImplemented() throws Exception {
        String blob = SigilB64.encode(new byte[1 + 33 + 12 + 16]);
        for (String t : Arrays.asList("S2K.abcd.efgh." + blob, "S2E.abcd." + blob)) {
            try {
                SigilCodec.open(t, ring);
                fail(t);
            } catch (SigilException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("not implemented"));
            }
        }
    }

    static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }
}
