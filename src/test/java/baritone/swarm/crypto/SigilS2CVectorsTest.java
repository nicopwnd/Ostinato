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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Checks the Java S2C codec against the pinned sigil 0.4.0 vectors
 * ({@code sigil-s2c-vectors.json}, see {@code SIGIL_PIN.md}).
 * The passphrases in that file are PUBLIC TEST-ONLY values, not keys.
 */
public class SigilS2CVectorsTest {

    private static JsonObject vectors;
    private static final Map<String, SigilCircle> CIRCLES = new LinkedHashMap<>();

    @BeforeClass
    public static void load() throws Exception {
        InputStream in = SigilS2CVectorsTest.class.getResourceAsStream("sigil-s2c-vectors.json");
        assertNotNull("pinned S2C vectors missing from test resources", in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        vectors = new JsonParser().parse(new String(out.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
        for (JsonElement e : vectors.getAsJsonArray("circles")) {
            JsonObject c = e.getAsJsonObject();
            CIRCLES.put(s(c, "id"), SigilCircle.derive(s(c, "name"), s(c, "passphrase")));
        }
    }

    static String s(JsonObject o, String k) {
        return o.get(k).getAsString();
    }

    static byte[] unhex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    private static List<SigilCircle> keyring(JsonObject v) {
        List<SigilCircle> k = new ArrayList<>();
        for (JsonElement e : v.getAsJsonArray("keyring")) {
            k.add(CIRCLES.get(e.getAsString()));
        }
        return k;
    }

    @Test
    public void headerAndCounts() {
        assertTrue(s(vectors, "WARNING").contains("PUBLIC TEST-ONLY"));
        assertEquals(SigilS2C.VERSION, s(vectors, "protocol"));
        assertEquals(SigilS2C.PROTOCOL, s(vectors, "aad_prefix"));
        assertEquals(SigilCodebookV2.LEXICON_SHA256, s(vectors, "lexicon_v2_sha256"));
        assertEquals(SigilCircle.PBKDF2_ITERATIONS, vectors.getAsJsonObject("kdf").get("iterations").getAsInt());
        assertEquals(21, vectors.getAsJsonArray("positive").size());
        assertEquals(21, vectors.getAsJsonArray("negative").size());
        assertEquals(4, vectors.getAsJsonArray("messages").size());
        for (JsonElement e : vectors.getAsJsonArray("circles")) {
            JsonObject c = e.getAsJsonObject();
            SigilCircle circle = CIRCLES.get(s(c, "id"));
            assertEquals(s(c, "key_hex"), SigilS2C.hex(circle.key())); // same key as S1
            assertEquals(s(c, "slug"), circle.slug());
        }
    }

    @Test
    public void everyPositiveVectorOpensAndRejoins() throws Exception {
        int parts = 0;
        for (JsonElement ve : vectors.getAsJsonArray("positive")) {
            JsonObject v = ve.getAsJsonObject();
            String id = s(v, "id");
            List<SigilCircle> keyring = keyring(v);
            JsonArray lines = v.getAsJsonArray("lines");
            JsonArray ps = v.getAsJsonArray("parts");
            assertEquals(id, lines.size(), ps.size());
            List<SigilS2C.Opened> opened = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                JsonObject p = ps.get(i).getAsJsonObject();
                SigilS2C.Opened o = SigilS2C.open(lines.get(i).getAsString(), keyring);
                assertEquals(id, CIRCLES.get(s(v, "circle")), o.circle());
                assertEquals(id, p.get("index").getAsInt(), o.index());
                assertEquals(id, p.get("total").getAsInt(), o.total());
                assertEquals(id, p.get("flags").getAsInt(), o.flags());
                assertEquals(id, p.get("codebook").getAsBoolean(), o.codebook());
                assertEquals(id, p.get("join").getAsBoolean(), o.join());
                assertEquals(id, s(p, "header_hex"), SigilS2C.hex(o.header()));
                assertEquals(id, s(p, "mid_hex"), o.midHex());
                assertEquals(id, s(p, "nonce_hex"), SigilS2C.hex(o.nonce()));
                assertEquals(id, s(p, "payload_hex"), SigilS2C.hex(o.plaintext()));
                assertEquals(id, s(p, "aad_hex"), SigilS2C.hex(SigilS2C.aad(o.header(), o.circle().name())));
                assertEquals(id, s(p, "plaintext"), o.text());
                if (p.get("sender").isJsonNull()) {
                    assertNull(id, o.sender());
                } else {
                    assertEquals(id, s(p, "sender"), o.sender());
                }
                // The facade opens it too.
                SigilCodec.Opened f = SigilCodec.open(lines.get(i).getAsString(), keyring);
                assertEquals(id, SigilWire.S2, f.wire());
                assertEquals(id, o.text(), f.text());
                opened.add(o);
                parts++;
            }
            List<SigilS2C.Message> msgs = SigilS2C.assemble(opened);
            assertEquals(id, 1, msgs.size());
            assertTrue(id, msgs.get(0).complete());
            assertEquals(id, s(v, "joined"), msgs.get(0).text());
        }
        assertTrue(parts > 21);
    }

    @Test
    public void sealingReproducesRecordedBytes() throws Exception {
        int rawVectors = 0;
        for (JsonElement ve : vectors.getAsJsonArray("positive")) {
            JsonObject v = ve.getAsJsonObject();
            String id = s(v, "id");
            SigilCircle circle = CIRCLES.get(s(v, "circle"));
            JsonArray ps = v.getAsJsonArray("parts");
            List<byte[]> nonces = new ArrayList<>();
            List<String> tokens = new ArrayList<>();
            for (JsonElement pe : ps) {
                JsonObject p = pe.getAsJsonObject();
                // Every part (compact ones included): header + payload + nonce -> identical token.
                String token = SigilS2C.sealFrame(circle, unhex(s(p, "header_hex")), unhex(s(p, "payload_hex")),
                        unhex(s(p, "nonce_hex")));
                assertEquals(id, s(p, "token"), token);
                nonces.add(unhex(s(p, "nonce_hex")));
                tokens.add(token);
            }
            JsonObject seal = v.getAsJsonObject("seal");
            if (seal.get("compact").getAsBoolean()) {
                continue; // the Java sealer never emits codebook parts
            }
            rawVectors++;
            byte[] mid = unhex(s(ps.get(0).getAsJsonObject(), "mid_hex"));
            List<String> lines = SigilS2C.sealWithRandom(circle, s(v, "plaintext"), s(seal, "sender"),
                    seal.get("max_line").getAsInt(), mid, nonces);
            assertEquals(id + ": same split and bytes as seal_circle_s2", tokens, lines);
        }
        assertEquals(15, rawVectors);
    }

    @Test
    public void everyNegativeVectorFails() throws Exception {
        for (JsonElement ve : vectors.getAsJsonArray("negative")) {
            JsonObject v = ve.getAsJsonObject();
            String line = s(v, "line");
            try {
                SigilCodec.Opened o = SigilCodec.open(line, keyring(v));
                fail(s(v, "id") + " opened: " + o.text());
            } catch (SigilException expected) {
                assertTrue(expected.getMessage() != null);
            }
        }
    }

    @Test
    public void wholeMessageVectors() throws Exception {
        for (JsonElement ve : vectors.getAsJsonArray("messages")) {
            JsonObject v = ve.getAsJsonObject();
            String id = s(v, "id");
            List<SigilS2C.Opened> opened = new ArrayList<>();
            for (JsonElement l : v.getAsJsonArray("lines")) {
                opened.add(SigilS2C.open(l.getAsString(), keyring(v))); // every line opens on its own
            }
            List<String> complete = new ArrayList<>();
            for (SigilS2C.Message m : SigilS2C.assemble(opened)) {
                if (m.complete()) {
                    complete.add(m.text());
                }
            }
            List<String> expected = new ArrayList<>();
            for (JsonElement e : v.getAsJsonArray("complete")) {
                expected.add(e.getAsString());
            }
            assertEquals(id, expected, complete);
        }
    }

    @Test
    public void s1FrameRelabelledAsS2DoesNotOpenEvenWithTheRightKey() throws Exception {
        JsonObject neg = null;
        for (JsonElement ve : vectors.getAsJsonArray("negative")) {
            if (s(ve.getAsJsonObject(), "id").equals("s1-frame-relabelled-s2")) {
                neg = ve.getAsJsonObject();
            }
        }
        assertNotNull(neg);
        String blob = s(neg, "line").substring(s(neg, "line").lastIndexOf('.') + 1);
        SigilB64.decode(blob); // well-formed base64url; only the AAD domain differs
        try {
            SigilS2C.open(s(neg, "line"), keyring(neg));
            fail();
        } catch (SigilException expected) {
        }
    }
}
