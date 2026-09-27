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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads circle records written by {@code sigil circle new} ({@code $SIGIL_HOME/circle-*.json}).
 *
 * <p>Records are refused unless {@code kind == "circle"} and
 * {@code kdf == "PBKDF2-HMAC-SHA256/210000"}; a record's stored fingerprint
 * must match the re-derived key. This is how "weaker" keyrings are rejected:
 * there is no code path that derives with fewer iterations.
 *
 * <p>The keyring holds passphrases in plaintext (sigil's format). Never copy
 * it into a repository, the game directory, or a config file.
 */
public final class SigilKeyring {

    private SigilKeyring() {}

    /** Parse one record (JSON object text) and derive its key. */
    public static SigilCircle parseCircleRecord(String json) throws SigilException {
        JsonObject o;
        try {
            JsonElement e = new JsonParser().parse(json);
            if (!e.isJsonObject()) {
                throw new SigilException("Keyring record is not a JSON object.");
            }
            o = e.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new SigilException("Keyring record is not valid JSON.");
        }
        String kind = str(o, "kind");
        if (!"circle".equals(kind)) {
            throw new SigilException("Keyring record is not a circle (kind=" + kind + ").");
        }
        String kdf = str(o, "kdf");
        if (!SigilCircle.KDF_ID.equals(kdf)) {
            throw new SigilException("Refusing circle record with kdf '" + kdf + "'; only "
                    + SigilCircle.KDF_ID + " is accepted.");
        }
        String name = str(o, "name");
        String passphrase = str(o, "passphrase");
        if (name == null || passphrase == null) {
            throw new SigilException("Circle record missing name or passphrase.");
        }
        SigilCircle circle = SigilCircle.derive(name, passphrase);
        String fp = str(o, "fingerprint");
        if (fp == null || !fp.equals(circle.fingerprint())) {
            throw new SigilException("Circle record fingerprint does not match its passphrase (corrupt or tampered).");
        }
        String slug = str(o, "slug");
        if (slug != null && !slug.equals(circle.slug())) {
            throw new SigilException("Circle record slug does not match its name.");
        }
        return circle;
    }

    /** Load every {@code circle-*.json} in a sigil home directory, sorted by file name like sigil. */
    public static List<SigilCircle> loadCircles(Path sigilHome) throws SigilException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sigilHome, "circle-*.json")) {
            for (Path p : ds) {
                files.add(p);
            }
        } catch (IOException e) {
            throw new SigilException("Cannot list keyring directory.", e);
        }
        files.sort(null);
        List<SigilCircle> out = new ArrayList<>();
        for (Path p : files) {
            try {
                out.add(parseCircleRecord(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new SigilException("Cannot read keyring record " + p.getFileName() + ".", e);
            }
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
