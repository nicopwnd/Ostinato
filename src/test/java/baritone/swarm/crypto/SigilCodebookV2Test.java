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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Java {@code expand_v2} against sigil's own results ({@code sigil-codebook-v2-cases.json}). */
public class SigilCodebookV2Test {

    private static JsonObject cases() throws Exception {
        InputStream in = SigilCodebookV2Test.class.getResourceAsStream("sigil-codebook-v2-cases.json");
        assertNotNull(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return new JsonParser().parse(new String(out.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    public void lexiconIsThePinnedOne() throws Exception {
        assertEquals(SigilCodebookV2.WORD_COUNT, SigilCodebookV2.words().length);
        assertEquals(SigilCodebookV2.LEXICON_SHA256, cases().get("lexicon_v2_sha256").getAsString());
    }

    @Test
    public void expandMatchesSigilOnCompressedTexts() throws Exception {
        int n = 0;
        for (JsonElement e : cases().getAsJsonArray("texts")) {
            JsonObject c = e.getAsJsonObject();
            byte[] data = SigilS2CVectorsTest.unhex(c.get("hex").getAsString());
            assertEquals(c.get("text").getAsString(), c.get("expanded").getAsString(), SigilCodebookV2.expand(data));
            n++;
        }
        assertTrue(n >= 20);
    }

    @Test
    public void expandMatchesSigilOnRandomStreamsIncludingFailures() throws Exception {
        int ok = 0;
        int failed = 0;
        for (JsonElement e : cases().getAsJsonArray("fuzz")) {
            JsonObject c = e.getAsJsonObject();
            String hex = c.get("hex").getAsString();
            byte[] data = SigilS2CVectorsTest.unhex(hex);
            if (c.has("error")) {
                try {
                    String got = SigilCodebookV2.expand(data);
                    fail(hex + " should fail (" + c.get("error").getAsString() + ") but gave " + got);
                } catch (SigilException expected) {
                    failed++;
                }
            } else {
                assertEquals(hex, c.get("expanded").getAsString(), SigilCodebookV2.expand(data));
                ok++;
            }
        }
        assertTrue(ok > 100 && failed > 100);
    }

    @Test
    public void rejectsNonV2() {
        for (byte[] bad : new byte[][]{{}, {(byte) 0xC1, 0}, {'h', 'i'}}) {
            try {
                SigilCodebookV2.expand(bad);
                fail();
            } catch (SigilException expected) {
            }
        }
    }
}
