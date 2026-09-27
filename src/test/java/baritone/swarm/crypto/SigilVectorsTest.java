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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Checks the Java S1C codec against the pinned sigil vectors
 * ({@code sigil-s1c-vectors.json}, see {@code SIGIL_PIN.md}).
 * The passphrases in that file are PUBLIC TEST-ONLY values, not keys.
 */
public class SigilVectorsTest {

    private static JsonObject vectors;
    private static final Map<String, SigilCircle> CIRCLES = new LinkedHashMap<>();
    private static final Map<String, JsonObject> CIRCLE_JSON = new LinkedHashMap<>();

    @BeforeClass
    public static void load() throws Exception {
        InputStream in = SigilVectorsTest.class.getResourceAsStream("sigil-s1c-vectors.json");
        assertNotNull("pinned vectors missing from test resources", in);
        vectors = new JsonParser().parse(new String(readAll(in), StandardCharsets.UTF_8)).getAsJsonObject();
        for (JsonElement e : vectors.getAsJsonArray("circles")) {
            JsonObject c = e.getAsJsonObject();
            CIRCLE_JSON.put(s(c, "id"), c);
            CIRCLES.put(s(c, "id"), SigilCircle.derive(s(c, "name"), s(c, "passphrase")));
        }
    }

    @Test
    public void headerMatchesCodecConstants() {
        assertTrue(s(vectors, "WARNING").contains("PUBLIC TEST-ONLY"));
        assertEquals(SigilS1C.VERSION, s(vectors, "protocol"));
        assertEquals(SigilS1C.PROTOCOL, s(vectors, "aad_prefix"));
        assertEquals(SigilCircle.PBKDF2_ITERATIONS, vectors.getAsJsonObject("kdf").get("iterations").getAsInt());
        assertEquals(SigilS1C.NONCE_LEN, vectors.getAsJsonObject("seal").get("nonce_len").getAsInt());
        assertEquals(SigilS1C.TAG_LEN, vectors.getAsJsonObject("seal").get("tag_len").getAsInt());
    }

    @Test
    public void circlesDeriveIdenticalKeys() {
        for (Map.Entry<String, JsonObject> e : CIRCLE_JSON.entrySet()) {
            JsonObject c = e.getValue();
            SigilCircle circle = CIRCLES.get(e.getKey());
            assertEquals(e.getKey(), s(c, "key_hex"), hex(circle.key()));
            assertEquals(e.getKey(), s(c, "slug"), circle.slug());
            assertEquals(e.getKey(), s(c, "fingerprint"), circle.fingerprint());
            assertEquals(e.getKey(), SigilCircle.KDF_ID, s(c, "kdf"));
        }
    }

    @Test
    public void everyPositiveVectorOpens() throws Exception {
        int parts = 0;
        for (JsonElement ve : vectors.getAsJsonArray("positive")) {
            JsonObject v = ve.getAsJsonObject();
            String id = s(v, "id");
            List<SigilCircle> keyring = keyring(v);
            JsonArray lines = v.getAsJsonArray("lines");
            JsonArray ps = v.getAsJsonArray("parts");
            assertEquals(id, lines.size(), ps.size());
            for (int i = 0; i < lines.size(); i++) {
                JsonObject p = ps.get(i).getAsJsonObject();
                SigilS1C.Opened o = SigilS1C.open(lines.get(i).getAsString(), keyring);
                assertEquals(id, s(CIRCLE_JSON.get(s(v, "circle")), "name"), o.circle().name());
                assertEquals(id, p.get("index").getAsInt(), o.index());
                assertEquals(id, p.get("total").getAsInt(), o.total());
                assertEquals(id, s(p, "payload_hex"), hex(o.payload()));
                boolean lenient = p.has("opened_compact_flag");
                assertEquals(id, p.get("compact").getAsBoolean() || lenient, o.headerCompact());
                assertEquals(id, p.get("compact").getAsBoolean(), o.payloadCompact());
                if (!o.payloadCompact()) {
                    assertEquals(id, s(p, "plaintext"), o.text());
                } else {
                    assertEquals(id + " codebook v2 magic", (byte) 0xC2, o.payload()[0]);
                    try {
                        o.text();
                        fail(id + ": text() must refuse codebook payloads");
                    } catch (SigilException expected) {
                        // Java codec does not expand codebook v2.
                    }
                }
                parts++;
            }
        }
        assertTrue("expected a non-trivial vector set", parts >= 20);
    }

    @Test
    public void everyPositivePartResealsByteIdentical() throws Exception {
        for (JsonElement ve : vectors.getAsJsonArray("positive")) {
            JsonObject v = ve.getAsJsonObject();
            SigilCircle circle = CIRCLES.get(s(v, "circle"));
            for (JsonElement pe : v.getAsJsonArray("parts")) {
                JsonObject p = pe.getAsJsonObject();
                String token = SigilS1C.sealPart(circle, unhex(s(p, "payload_hex")), p.get("compact").getAsBoolean(),
                        p.get("index").getAsInt(), p.get("total").getAsInt(), unhex(s(p, "nonce_hex")));
                assertEquals(s(v, "id") + " aad", s(p, "aad"), new String(SigilS1C.aad(circle.name(),
                        p.get("compact").getAsBoolean(), p.get("index").getAsInt(), p.get("total").getAsInt()),
                        StandardCharsets.UTF_8));
                String expected = s(p, "token");
                if (p.has("opened_compact_flag")) {
                    // lenient vector: '.z' was inserted after sealing
                    expected = expected.replace("S1C." + circle.slug() + ".z.", "S1C." + circle.slug() + ".");
                }
                assertEquals(s(v, "id"), expected, token);
            }
        }
    }

    @Test
    public void plainChunkingMatchesSigil() throws Exception {
        int checked = 0;
        for (JsonElement ve : vectors.getAsJsonArray("positive")) {
            JsonObject v = ve.getAsJsonObject();
            JsonObject seal = v.getAsJsonObject("seal");
            String id = s(v, "id");
            if (seal.get("compact").getAsBoolean() || id.startsWith("lenient")) {
                continue;
            }
            List<byte[]> nonces = new ArrayList<>();
            List<String> tokens = new ArrayList<>();
            for (JsonElement pe : v.getAsJsonArray("parts")) {
                nonces.add(unhex(s(pe.getAsJsonObject(), "nonce_hex")));
                tokens.add(s(pe.getAsJsonObject(), "token"));
            }
            List<String> got = SigilS1C.sealWithNonces(CIRCLES.get(s(v, "circle")), s(v, "plaintext"),
                    seal.get("max_line").getAsInt(), nonces);
            assertEquals(id, tokens, got);
            checked++;
        }
        assertTrue(checked >= 10);
    }

    @Test
    public void everyNegativeVectorFails() {
        for (JsonElement ve : vectors.getAsJsonArray("negative")) {
            JsonObject v = ve.getAsJsonObject();
            try {
                SigilS1C.open(s(v, "line"), keyring(v));
                fail("negative vector opened: " + s(v, "id"));
            } catch (SigilException expected) {
                assertFalse(expected.getMessage().contains(s(CIRCLE_JSON.get("main"), "passphrase")));
            }
        }
        assertTrue(vectors.getAsJsonArray("negative").size() >= 10);
    }

    // ---------------------------------------------------------------- helpers

    private static List<SigilCircle> keyring(JsonObject v) {
        List<SigilCircle> out = new ArrayList<>();
        for (JsonElement e : v.getAsJsonArray("keyring")) {
            out.add(CIRCLES.get(e.getAsString()));
        }
        return out;
    }

    static String s(JsonObject o, String k) {
        return o.get(k).getAsString();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    static byte[] unhex(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        in.close();
        return bo.toByteArray();
    }
}
