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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Round-trip and keyring-policy tests for the Java S1C codec. */
public class SigilS1CTest {

    // PUBLIC TEST-ONLY values (same as the sigil vectors). Not keys.
    private static final String NAME = "sigil-vector-test";
    private static final String PASS = "sigil-public-test-vector-passphrase-DO-NOT-USE";

    private static SigilCircle circle;
    private static SigilCircle other;

    @BeforeClass
    public static void derive() throws Exception {
        circle = SigilCircle.derive(NAME, PASS);
        other = SigilCircle.derive("sigil-vector-other", PASS);
    }

    @Test
    public void roundTripAcrossLengthsAndUnicode() throws Exception {
        Random r = new Random(42);
        String alphabet = "abc XYZ 019 -_/.,:é北🧭\n";
        int[] cps = alphabet.codePoints().toArray();
        for (int len = 0; len <= 500; len += 7) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                sb.appendCodePoint(cps[r.nextInt(cps.length)]);
            }
            String text = sb.toString();
            for (int maxLine : new int[]{256, 232, 180, 100}) {
                List<String> lines = SigilS1C.seal(circle, text, maxLine);
                StringBuilder back = new StringBuilder();
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (maxLine == SigilS1C.MINECRAFT_MAX_LINE) {
                        assertTrue("line too long: " + line.length(), line.length() <= maxLine);
                    }
                    SigilS1C.Opened o = SigilS1C.open(line, Collections.singletonList(circle));
                    assertEquals(i + 1, o.index());
                    assertEquals(lines.size(), o.total());
                    assertFalse(o.payloadCompact());
                    back.append(o.text());
                }
                assertEquals("len=" + len + " maxLine=" + maxLine, text, back.toString());
            }
        }
    }

    @Test
    public void freshNonceEverySeal() throws Exception {
        String a = SigilS1C.sealSingle(circle, "same text", 256);
        String b = SigilS1C.sealSingle(circle, "same text", 256);
        assertNotEquals(a, b);
        assertEquals("same text", SigilS1C.open(a, Collections.singletonList(circle)).text());
        assertEquals("same text", SigilS1C.open(b, Collections.singletonList(circle)).text());
    }

    @Test
    public void sealSingleRefusesMultiLine() throws Exception {
        char[] big = new char[400];
        Arrays.fill(big, 'q');
        try {
            SigilS1C.sealSingle(circle, new String(big), 256);
            fail("expected SigilException");
        } catch (SigilException expected) {
            // needs more than one line
        }
    }

    @Test
    public void nameIsBoundEvenWhenSlugCollides() throws Exception {
        assertEquals(circle.slug(), other.slug());
        String t = SigilS1C.sealSingle(other, "for other only", 256);
        try {
            SigilS1C.open(t, Collections.singletonList(circle));
            fail("opened under the wrong circle");
        } catch (SigilException expected) {
            // GCM tag rejects it
        }
        assertEquals("for other only", SigilS1C.open(t, Arrays.asList(circle, other)).text());
    }

    @Test
    public void refusesUnimplementedModes() {
        for (String line : new String[]{"S1K.abcd.efgh.AAAA", "S1E.abcd.AAAA", "S1X.abcd.AAAA"}) {
            try {
                SigilS1C.open(line, Collections.singletonList(circle));
                fail(line);
            } catch (SigilException expected) {
                // not supported
            }
        }
    }

    // ------------------------------------------------------------- keyring

    private static String record(String kind, String kdf, String fp) {
        return "{\"kind\":\"" + kind + "\",\"name\":\"" + NAME + "\",\"slug\":\"" + circle.slug()
                + "\",\"fingerprint\":\"" + fp + "\",\"kdf\":\"" + kdf + "\",\"passphrase\":\"" + PASS + "\"}";
    }

    @Test
    public void keyringAcceptsSigilRecord() throws Exception {
        SigilCircle c = SigilKeyring.parseCircleRecord(record("circle", SigilCircle.KDF_ID, circle.fingerprint()));
        assertEquals(circle.fingerprint(), c.fingerprint());
        assertEquals(NAME, c.name());
    }

    @Test
    public void keyringRefusesWeakerOrForeignKdf() {
        for (String kdf : new String[]{"PBKDF2-HMAC-SHA256/1000", "PBKDF2-HMAC-SHA256/209999",
                "PBKDF2-HMAC-SHA1/210000", "scrypt", ""}) {
            try {
                SigilKeyring.parseCircleRecord(record("circle", kdf, circle.fingerprint()));
                fail("accepted kdf " + kdf);
            } catch (SigilException expected) {
                assertTrue(expected.getMessage().contains("Refusing"));
            }
        }
        try {
            SigilKeyring.parseCircleRecord("{\"kind\":\"circle\",\"name\":\"n\",\"passphrase\":\"p\"}");
            fail("accepted record without kdf");
        } catch (SigilException expected) {
            // missing kdf
        }
    }

    @Test
    public void keyringRefusesTamperedOrWrongKind() {
        try {
            SigilKeyring.parseCircleRecord(record("circle", SigilCircle.KDF_ID, "zzzz"));
            fail("accepted bad fingerprint");
        } catch (SigilException expected) {
            assertFalse(expected.getMessage().contains(PASS));
        }
        try {
            SigilKeyring.parseCircleRecord(record("signet", SigilCircle.KDF_ID, circle.fingerprint()));
            fail("accepted non-circle");
        } catch (SigilException expected) {
            // kind check
        }
    }

    @Test
    public void loadsSigilHomeDirectory() throws Exception {
        Path dir = Files.createTempDirectory("sigil-home-test-");
        File f = dir.resolve("circle-sigilvectort.json").toFile();
        try {
            Files.write(f.toPath(), record("circle", SigilCircle.KDF_ID, circle.fingerprint())
                    .getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("signet-steve.json"), "{}".getBytes(StandardCharsets.UTF_8));
            List<SigilCircle> ring = SigilKeyring.loadCircles(dir);
            assertEquals(1, ring.size());
            String t = SigilS1C.sealSingle(circle, "from disk", 256);
            assertEquals("from disk", SigilS1C.open(t, ring).text());
        } finally {
            Files.deleteIfExists(dir.resolve("signet-steve.json"));
            Files.deleteIfExists(f.toPath());
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void toStringNeverLeaksKey() {
        String s = circle.toString();
        assertFalse(s.contains(PASS));
        assertFalse(s.toLowerCase().contains(SigilVectorsTest.hex(circle.key())));
    }
}
