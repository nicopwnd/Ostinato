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


package baritone.swarm.roster;

import baritone.swarm.transport.SwarmChannel;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SwarmRosterTest {

    private static final String SAMPLE = String.join("\n",
            "# swarm roster: names only, never keys",
            "",
            "group command circle=swarm-cmd members=Alice,Bob lead=Alice",
            "  group builders   circle=swarm-build members=Bob,Carol,Dave_2 lead=Bob parent=command transport=team",
            "group scouts circle=swarm-scout members=Erin parent=command",
            "group solo circle=swarm-solo members=Zed parent=-",
            "");

    @Test
    public void parsesGroups() throws Exception {
        SwarmRoster r = SwarmRoster.parse(SAMPLE);
        assertEquals(4, r.groups().size());
        SwarmRoster.Group b = r.group("builders");
        assertEquals("swarm-build", b.circle());
        assertEquals(Arrays.asList("Bob", "Carol", "Dave_2"), b.members());
        assertEquals("Bob", b.lead());
        assertEquals("command", b.parent());
        assertEquals(SwarmChannel.TEAM, b.channel());
        assertNull(r.group("command").parent());
        assertNull(r.group("command").channel());
        assertNull(r.group("solo").parent());
        assertTrue(r.isMember("builders", "Carol"));
        assertFalse(r.isMember("builders", "carol")); // exact names
        assertFalse(r.isMember("nope", "Carol"));
        assertEquals(2, r.groupsOf("Bob").size());
        assertEquals("command", r.groupsOf("Bob").get(0).id());
        assertEquals(0, r.groupsOf("Mallory").size());
    }

    @Test
    public void windowsLineEndingsAndEmpty() throws Exception {
        assertEquals(1, SwarmRoster.parse("group a circle=c members=Alice\r\n# x\r\n").groups().size());
        assertEquals(0, SwarmRoster.parse("# nothing\n\n").groups().size());
    }

    private static void bad(String text, String expect, int line) {
        try {
            SwarmRoster.parse(text);
            fail("accepted: " + text);
        } catch (SwarmRosterException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expect));
            assertEquals(line, e.line());
        }
    }

    @Test
    public void rejectsBadLines() {
        bad("grp a circle=c members=A1", "expected 'group", 1);
        bad("group", "expected 'group", 1);
        bad("# ok\ngroup bad.id circle=c members=Alice", "bad group id", 2);
        bad("group a circle=c members=Alice\ngroup a circle=d members=Bob", "duplicate group", 2);
        bad("group a circle=c members=Alice colour=red", "unknown key", 1);
        bad("group a circle=c members=Alice circle=d", "duplicate key", 1);
        bad("group a members=Alice", "needs circle", 1);
        bad("group a circle= members=Alice", "needs circle", 1);
        bad("group a circle=c", "needs members", 1);
        bad("group a circle=c members=", "needs members", 1);
        bad("group a circle=c members=Alice,,Bob", "bad member", 1);
        bad("group a circle=c members=Al-ice", "bad member", 1);
        bad("group a circle=c members=ThisNameIsTooLong17", "bad member", 1);
        bad("group a circle=c members=Alice,alice", "listed twice", 1);
        bad("group a circle=c members=Alice lead=Bob", "not a member", 1);
        bad("group a circle=c members=Alice transport=carrierpigeon", "transport must be", 1);
        bad("group a circle=c members=Alice oops", "key=value", 1);
    }

    @Test
    public void rejectsSharedCircle() {
        bad("group a circle=c members=Alice\ngroup b circle=c members=Bob", "share circle", 2);
    }

    @Test
    public void rejectsUnknownParentAndCycles() {
        bad("group a circle=c members=Alice parent=zz", "unknown parent", 0);
        bad("group a circle=c members=Alice parent=a", "cycle", 0);
        bad("group a circle=c members=Alice parent=b\ngroup b circle=d members=Bob parent=c\n"
                + "group c circle=e members=Carol parent=a", "cycle", 0);
    }
}
