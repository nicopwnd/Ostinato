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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SwarmFrameTest {

    @Test
    public void encodeDecodeRoundTrip() throws Exception {
        SwarmFrame f = new SwarmFrame("mine", "bot_1", "bot-2", 1790000000L, 42, 1790000123L, 41, 2, 3, "task",
                "dig|x=1|y=2 \u00e9\u4e16\ud83d\ude00");
        String text = f.encode();
        assertEquals("1|mine|bot_1|bot-2|tlpwu8|16|tlpwxn|15|2/3|task|dig|x=1|y=2 \u00e9\u4e16\ud83d\ude00", text);
        SwarmFrame g = SwarmFrame.decode(text);
        assertEquals(f.encode(), g.encode());
        assertEquals("mine", g.group());
        assertEquals("bot_1", g.from());
        assertEquals("bot-2", g.to());
        assertEquals(1790000000L, g.epoch());
        assertEquals(42, g.seq());
        assertEquals(1790000123L, g.ts());
        assertEquals(41, g.msgId());
        assertEquals(2, g.index());
        assertEquals(3, g.total());
        assertEquals("task", g.type());
        assertEquals("dig|x=1|y=2 \u00e9\u4e16\ud83d\ude00", g.body());
    }

    @Test
    public void broadcastAndEmptyBody() throws Exception {
        SwarmFrame f = SwarmFrame.decode("1|g|a|*|0|1|0|1|1/1|ping|");
        assertEquals(SwarmFrame.BROADCAST, f.to());
        assertEquals("", f.body());
        assertEquals("1|g|a|*|0|1|0|1|1/1|ping|", f.encode());
    }

    @Test
    public void rejectsMalformed() {
        String[] bad = {
                "",
                "2|g|a|b|0|1|0|1|1/1|t|x",           // version
                "1|g|a|b|0|1|0|1|1/1|t",             // too few fields
                "1||a|b|0|1|0|1|1/1|t|x",            // empty group
                "1|g|../x|b|0|1|0|1|1/1|t|x",        // path chars in id
                "1|g|a|b c|0|1|0|1|1/1|t|x",         // space in id
                "1|g|aaaaaaaaaaaaaaaaa|b|0|1|0|1|1/1|t|x", // 17-char id
                "1|g|*|b|0|1|0|1|1/1|t|x",           // broadcast is not a sender
                "1|g|a|b|0|1|0|1|1/1|typetoolong|x", // type > 8
                "1|g|a|b|0|1|0|1|1/1|t-x|x",         // '-' not allowed in type
                "1|g|a|b|0|1|0|1|0/1|t|x",           // i = 0
                "1|g|a|b|0|2|0|1|2/1|t|x",           // i > n
                "1|g|a|b|0|1|0|1|01/1|t|x",          // leading zero
                "1|g|a|b|0|1|0|1|1/1000|t|x",        // n > 999
                "1|g|a|b|0|1|0|1|11|t|x",            // no slash
                "1|g|a|b|0|Z|0|1|1/1|t|x",           // uppercase base36
                "1|g|a|b|-1|1|0|1|1/1|t|x",          // sign
                "1|g|a|b|0|0|0|0|1/1|t|x",           // seq 0
                "1|g|a|b|0|zzzzzzzzzzzzz|0|1|1/1|t|x", // overflow
        };
        for (String s : bad) {
            try {
                SwarmFrame.decode(s);
                fail("accepted: " + s);
            } catch (SwarmFrameException e) {
                assertEquals(s, SwarmReject.MALFORMED, e.reason());
            }
        }
    }

    @Test
    public void seqMustBelongToMessageId() {
        // part 2 of msg 5 must carry seq 6; seq 9 means it was lifted from another message
        try {
            SwarmFrame.decode("1|g|a|b|0|9|0|5|2/3|t|x");
            fail();
        } catch (SwarmFrameException e) {
            assertEquals(SwarmReject.SPLICE, e.reason());
        }
    }
}
