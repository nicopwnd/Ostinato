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
import baritone.swarm.crypto.SigilException;
import baritone.swarm.roster.SwarmRoster;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Keyrings here are written to a temp dir with public DO-NOT-USE passphrases. */
public class SwarmKeysTest {

    private static final String PASS = "public-test-passphrase-DO-NOT-USE";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(Path home, SigilCircle c, String fingerprint) throws Exception {
        String json = "{\"kind\":\"circle\",\"name\":\"" + c.name() + "\",\"slug\":\"" + c.slug()
                + "\",\"fingerprint\":\"" + fingerprint + "\",\"kdf\":\"" + SigilCircle.KDF_ID
                + "\",\"passphrase\":\"" + PASS + "\"}";
        Files.write(home.resolve("circle-" + c.name() + ".json"), json.getBytes(StandardCharsets.UTF_8));
    }

    private static SwarmRoster roster() throws Exception {
        return SwarmRoster.parse("group crew circle=swarm-test-alpha members=Alice,Bob\n"
                + "group other circle=swarm-test-beta members=Carol\n");
    }

    @Test
    public void resolvesOnlyMyGroupsAndSkipsOtherRecords() throws Exception {
        Path home = tmp.newFolder("sigil-home").toPath();
        write(home, TestCircles.alpha(), TestCircles.alpha().fingerprint());
        // a broken record for a circle Alice does not use is never derived, so it cannot fail her load
        write(home, TestCircles.beta(), "0000000000000000");
        Map<String, SigilCircle> got = SwarmKeys.circlesFor(roster(), "Alice", home);
        assertEquals(1, got.size());
        assertEquals(TestCircles.alpha().fingerprint(), got.get("crew").fingerprint());
        try {
            SwarmKeys.circlesFor(roster(), "Carol", home); // Carol needs beta, whose record is bad
            fail("accepted a tampered record");
        } catch (SigilException expected) {
            assertTrue(expected.getMessage().contains("fingerprint"));
        }
    }

    @Test
    public void explainsMissingPieces() throws Exception {
        Path home = tmp.newFolder("empty-home").toPath();
        expect(() -> SwarmKeys.circlesFor(roster(), "Alice", null), "No sigil home");
        expect(() -> SwarmKeys.circlesFor(roster(), "Alice", home.resolve("nope")), "not a directory");
        expect(() -> SwarmKeys.circlesFor(roster(), "Mallory", home), "not a member");
        expect(() -> SwarmKeys.circlesFor(roster(), "Alice", home), "not in the keyring");
    }

    @Test
    public void refusesDuplicateRecords() throws Exception {
        Path home = tmp.newFolder("dup-home").toPath();
        write(home, TestCircles.alpha(), TestCircles.alpha().fingerprint());
        Files.copy(home.resolve("circle-swarm-test-alpha.json"), home.resolve("circle-swarm-test-alpha-copy.json"));
        expect(() -> SwarmKeys.circlesFor(roster(), "Alice", home), "two records");
    }

    @Test
    public void homeFromSettingThenEnv() {
        assertEquals(Paths.get("/a"), SwarmKeys.sigilHome("/a", "/b"));
        assertEquals(Paths.get("/b"), SwarmKeys.sigilHome("  ", "/b"));
        assertNull(SwarmKeys.sigilHome("", null));
        assertNull(SwarmKeys.sigilHome(null, " "));
    }

    private interface Call {
        void run() throws Exception;
    }

    private static void expect(Call c, String msg) throws Exception {
        try {
            c.run();
            fail("expected " + msg);
        } catch (SigilException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(msg));
        }
    }
}
