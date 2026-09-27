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
import baritone.swarm.roster.SwarmRoster;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChatSwarmTransportTest {

    private static String tok;
    private static SwarmRoster roster;

    @BeforeClass
    public static void setUp() throws Exception {
        tok = SigilCodec.sealSingle(SigilWire.S2, TestCircles.alpha(), "1|crew|Alice|*|1|1|1|1|1/1|PING|ping", 234);
        roster = SwarmRoster.parse("group crew circle=c1 members=Alice,Bob,Carol\n"
                + "group ops circle=c2 members=Alice,Dave,Bob transport=team\n"
                + "group loud circle=c3 members=Alice,Erin transport=global\n");
    }

    private static ChatSwarmTransport chat(SwarmChannel ch, String template, int burst) {
        return new ChatSwarmTransport("Alice", ch, template, roster,
                new SwarmRateLimiter(0.5, burst, false, 64, 0), 256);
    }

    private static List<String> flush(ChatSwarmTransport t, long now) {
        List<String> out = new ArrayList<>();
        t.flush(now, out::add);
        return out;
    }

    @Test
    public void whisperBroadcastFansOutToGroupExceptSelf() throws Exception {
        ChatSwarmTransport t = chat(SwarmChannel.WHISPER, "", 5);
        t.sendTo("crew", SwarmTransport.BROADCAST, tok, SwarmPriority.NORMAL);
        assertEquals(Arrays.asList("/msg Bob " + tok, "/msg Carol " + tok), flush(t, 0));
    }

    @Test
    public void whisperBroadcastWithoutGroupReachesEveryRosterMemberOnce() throws Exception {
        ChatSwarmTransport t = chat(SwarmChannel.WHISPER, "", 8);
        t.send(SwarmTransport.BROADCAST, tok);
        assertEquals(Arrays.asList("/msg Bob " + tok, "/msg Carol " + tok, "/msg Dave " + tok, "/msg Erin " + tok),
                flush(t, 0));
    }

    @Test
    public void directWhisperAndCustomTemplate() throws Exception {
        ChatSwarmTransport t = chat(SwarmChannel.WHISPER, "/w {to} {msg}", 5);
        t.sendTo("crew", "Carol", tok, SwarmPriority.NORMAL);
        assertEquals(Collections.singletonList("/w Carol " + tok), flush(t, 0));
        ChatSwarmTransport bad = chat(SwarmChannel.WHISPER, "/w {msg}", 5); // no {to}: default
        bad.sendTo("crew", "Carol", tok, SwarmPriority.NORMAL);
        assertEquals(Collections.singletonList("/msg Carol " + tok), flush(bad, 0));
    }

    @Test
    public void globalAndTeamSendOneLine() throws Exception {
        ChatSwarmTransport g = chat(SwarmChannel.GLOBAL, "", 5);
        g.sendTo("crew", SwarmTransport.BROADCAST, tok, SwarmPriority.NORMAL);
        assertEquals(Collections.singletonList(tok), flush(g, 0));
        ChatSwarmTransport w = chat(SwarmChannel.WHISPER, "/w {to} {msg}", 5);
        w.sendTo("ops", SwarmTransport.BROADCAST, tok, SwarmPriority.NORMAL); // roster override: team
        w.sendTo("loud", "Erin", tok, SwarmPriority.NORMAL); // roster override: global
        assertEquals(Arrays.asList("/teammsg " + tok, tok), flush(w, 0));
        assertEquals(SwarmChannel.TEAM, w.channelFor("ops"));
        assertEquals(SwarmChannel.WHISPER, w.channelFor("unknown"));
        assertEquals("/teammsg {msg}", w.templateFor(SwarmChannel.TEAM));
    }

    @Test
    public void refusesUnsealedAndOverlongLines() throws Exception {
        ChatSwarmTransport t = chat(SwarmChannel.WHISPER, "", 5);
        try {
            t.sendTo("crew", "Bob", "hello Bob, plaintext", SwarmPriority.HIGH);
            fail("sent plaintext");
        } catch (IllegalArgumentException expected) {
            // no plaintext mode
        }
        ChatSwarmTransport tiny = new ChatSwarmTransport("Alice", SwarmChannel.WHISPER, "", roster,
                new SwarmRateLimiter(0.5, 5, false, 64, 0), tok.length() + 5);
        try {
            tiny.sendTo("crew", SwarmTransport.BROADCAST, tok, SwarmPriority.NORMAL);
            fail("sent an overlong line");
        } catch (IOException expected) {
            assertEquals(1, tiny.tooLong());
        }
        assertEquals(0, tiny.limiter().queued()); // nothing partially fanned out
    }

    @Test
    public void templateOverheadMatchesDefaultReserve() {
        assertEquals(22, ChatSwarmTransport.templateOverhead("/msg {to} {msg}"));
        assertEquals(0, ChatSwarmTransport.templateOverhead("{msg}"));
        assertEquals(9, ChatSwarmTransport.templateOverhead("/teammsg {msg}"));
    }

    @Test
    public void drainIsRateLimited() throws Exception {
        SwarmRoster big = SwarmRoster.parse("group crew circle=c members=Alice,B1,B2,B3,B4,B5,B6,B7,B8");
        ChatSwarmTransport t = new ChatSwarmTransport("Alice", SwarmChannel.WHISPER, "", big,
                new SwarmRateLimiter(0.5, 5, false, 64, 0), 256);
        t.sendTo("crew", SwarmTransport.BROADCAST, tok, SwarmPriority.NORMAL);
        assertEquals(5, flush(t, 0).size());
        assertEquals(0, flush(t, 1000).size());
        assertEquals(1, flush(t, 2000).size());
        assertEquals(2, flush(t, 6000).size());
    }

    @Test
    public void inboundQueueIsBounded() {
        ChatSwarmTransport t = chat(SwarmChannel.WHISPER, "", 5);
        assertEquals(1, t.onChat("Bob whispers to you: " + tok));
        assertEquals(0, t.onChat("Bob whispers to you: hi"));
        assertEquals(Collections.singletonList(tok), t.receive());
        assertEquals(0, t.receive().size());
        for (int i = 0; i < ChatSwarmTransport.INBOUND_MAX + 3; i++) {
            t.onChat("<Bob> " + tok);
        }
        assertEquals(ChatSwarmTransport.INBOUND_MAX, t.receive().size());
        assertTrue(t.inboundDropped() == 3);
    }
}
