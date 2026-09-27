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

import baritone.swarm.SwarmConfig;
import baritone.swarm.SwarmEndpoint;
import baritone.swarm.TestCircles;
import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.frame.SwarmFrame;
import baritone.swarm.frame.SwarmMessage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LocalSpoolTransportTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicLong clock = new AtomicLong(1_790_000_000_000L);

    private SwarmEndpoint endpoint(Path root, String id) throws Exception {
        Map<String, SigilCircle> groups = Collections.singletonMap("alpha", TestCircles.alpha());
        return new SwarmEndpoint(id, SwarmConfig.defaults(), groups, new LocalSpoolTransport(root, id), clock::get);
    }

    private static List<Path> files(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).collect(Collectors.toList());
        }
    }

    @Test
    public void twoEndpointsExchangeThroughTheSpool() throws Exception {
        Path root = tmp.newFolder("spool").toPath();
        SwarmEndpoint a = endpoint(root, "botA");
        SwarmEndpoint b = endpoint(root, "botB");
        String secret = "build region 3 at 100 64 -200 | layer 7 of 12 | \u4e16\u754c "
                + "and a long tail so this needs several frames .................................................."
                + "..............................................................................................";
        a.send("alpha", "botB", "task", secret);
        b.send("alpha", "botA", "ack", "ok");

        // Only sealed tokens touch the disk.
        List<Path> onDisk = files(root);
        assertTrue(onDisk.size() >= 3);
        for (Path p : onDisk) {
            String content = new String(Files.readAllBytes(p), StandardCharsets.US_ASCII);
            assertTrue(content, SwarmTransport.isSealed(content));
            assertFalse(content.contains("region"));
        }

        List<SwarmMessage> atB = b.poll();
        assertEquals(1, atB.size());
        assertEquals(secret, atB.get(0).body());
        assertEquals("botA", atB.get(0).from());
        List<SwarmMessage> atA = a.poll();
        assertEquals(1, atA.size());
        assertEquals("ok", atA.get(0).body());
        assertTrue("inboxes are drained", files(root).isEmpty());
        assertTrue(a.poll().isEmpty());
        assertTrue(b.poll().isEmpty());
    }

    @Test
    public void broadcastGoesToEveryOtherInbox() throws Exception {
        Path root = tmp.newFolder("spool").toPath();
        SwarmEndpoint a = endpoint(root, "botA");
        SwarmEndpoint b = endpoint(root, "botB");
        SwarmEndpoint c = endpoint(root, "botC");
        a.send("alpha", SwarmFrame.BROADCAST, "hello", "all hands");
        assertEquals("all hands", b.poll().get(0).body());
        assertEquals("all hands", c.poll().get(0).body());
        assertTrue(a.poll().isEmpty());
    }

    @Test
    public void mailWaitsForAMemberThatStartsLater() throws Exception {
        Path root = tmp.newFolder("spool").toPath();
        SwarmEndpoint a = endpoint(root, "botA");
        a.send("alpha", "botLate", "task", "queued");
        SwarmEndpoint late = endpoint(root, "botLate");
        List<SwarmMessage> got = new ArrayList<>(late.poll());
        assertEquals(1, got.size());
        assertEquals("queued", got.get(0).body());
    }

    @Test
    public void refusesPlaintextAndUnsafeIds() throws Exception {
        Path root = tmp.newFolder("spool").toPath();
        LocalSpoolTransport t = new LocalSpoolTransport(root, "botA");
        try {
            t.send("botB", "plain text");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        for (String bad : new String[]{"../evil", "a/b", "", ".."}) {
            try {
                new LocalSpoolTransport(root, bad);
                fail(bad);
            } catch (IllegalArgumentException expected) {
            }
        }
        assertTrue(files(root).isEmpty());
    }

    @Test
    public void spoolDirSettingOverridesDefault() throws Exception {
        Path def = tmp.newFolder("default").toPath();
        Path custom = tmp.newFolder("custom").toPath();
        SwarmConfig.Builder b = SwarmConfig.builder();
        b.localSpoolDir = custom.toString();
        LocalSpoolTransport.open(b.build(), def, "botA");
        assertTrue(Files.isDirectory(custom.resolve("botA")));
        LocalSpoolTransport.open(SwarmConfig.defaults(), def, "botB");
        assertTrue(Files.isDirectory(def.resolve("botB")));
    }
}
