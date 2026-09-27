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


package baritone.swarm.transport;

import baritone.swarm.TestCircles;
import baritone.swarm.crypto.SigilCodec;
import baritone.swarm.crypto.SigilWire;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChatTokensTest {

    private static String s2;
    private static String s1;
    private static String s2b;

    @BeforeClass
    public static void seal() throws Exception {
        s2 = SigilCodec.sealSingle(SigilWire.S2, TestCircles.alpha(), "1|g|a|*|1|1|1|1|1/1|PING|ping", 234);
        s2b = SigilCodec.sealSingle(SigilWire.S2, TestCircles.alpha(), "second frame body", 234);
        s1 = SigilCodec.sealSingle(SigilWire.S1, TestCircles.alpha(), "an S1 frame", 234);
        assertTrue(SwarmTransport.isSealed(s2) && SwarmTransport.isSealed(s1) && SwarmTransport.isSealed(s2b));
    }

    private static List<String> one(String t) {
        return Collections.singletonList(t);
    }

    @Test
    public void vanillaWhisper() {
        assertEquals(one(s2), ChatTokens.extract("Alice whispers to you: " + s2));
        assertEquals(one(s1), ChatTokens.extract("Alice whispers to you: " + s1));
    }

    @Test
    public void ownWhisperEchoStillParses() {
        // vanilla shows the sender "You whisper to Bob: ..."; the endpoint drops it as SELF
        assertEquals(one(s2), ChatTokens.extract("You whisper to Bob: " + s2));
    }

    @Test
    public void publicAndPluginFormats() {
        assertEquals(one(s2), ChatTokens.extract("<Alice> " + s2));
        assertEquals(one(s2), ChatTokens.extract("[Alice -> me] " + s2));
        assertEquals(one(s2), ChatTokens.extract("[Alice \u2192 me] " + s2));
        assertEquals(one(s2), ChatTokens.extract("From Alice: " + s2));
        assertEquals(one(s2), ChatTokens.extract("[MSG] [Owner] Alice \u00bb " + s2));
        assertEquals(one(s2), ChatTokens.extract("-> [Team] Alice: " + s2));
        assertEquals(one(s2), ChatTokens.extract("\u00a77[\u00a7cAlice\u00a77 -> \u00a7fme\u00a77] \u00a7f" + s2));
    }

    @Test
    public void trailingPunctuationAndQuotes() {
        assertEquals(one(s2), ChatTokens.extract("Alice: " + s2 + "."));
        assertEquals(one(s2), ChatTokens.extract("Alice: \"" + s2 + "\""));
        assertEquals(one(s2), ChatTokens.extract("(" + s2 + ")"));
    }

    @Test
    public void multipleTokensCappedPerLine() {
        assertEquals(Arrays.asList(s2, s1, s2b), ChatTokens.extract("<A> " + s2 + " " + s1 + ", " + s2b));
        String many = "<A> " + s2 + " " + s2 + " " + s2 + " " + s2 + " " + s2 + " " + s2;
        assertEquals(ChatTokens.MAX_PER_LINE, ChatTokens.extract(many).size());
    }

    @Test
    public void junkIgnored() {
        assertEquals(0, ChatTokens.extract(null).size());
        assertEquals(0, ChatTokens.extract("").size());
        assertEquals(0, ChatTokens.extract("Alice joined the game").size());
        assertEquals(0, ChatTokens.extract("<Bob> S2C.abcd.tooShort").size());
        assertEquals(0, ChatTokens.extract("<Bob> S3C.abcd." + s2.substring(9)).size());
        assertEquals(0, ChatTokens.extract("<Bob> S2K." + s2.substring(4)).size()); // keypair tokens are not swarm
        assertEquals(0, ChatTokens.extract("<Bob> XS2C" + s2.substring(3)).size()); // glued to a word
        assertEquals(0, ChatTokens.extract("<Bob> S2C.ABCD." + s2.substring(9)).size()); // slug is lowercase
        assertEquals(0, ChatTokens.extract("<Bob> " + s2 + "#tail").size()); // glued suffix
    }

    @Test
    public void sigilMultiPartFragmentsIgnored() {
        String frag = "S1C.ab12.2/3." + s1.substring(9);
        assertEquals(0, ChatTokens.extract("<A> " + frag).size());
    }
}
