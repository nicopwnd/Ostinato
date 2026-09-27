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

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/**
 * A SIGIL circle: a shared-passphrase key, derived exactly like {@code sigil.py}.
 *
 * <pre>
 * salt = SHA-256("SIGIL.v1.circle.salt." || name)[0:16]
 * key  = PBKDF2-HMAC-SHA256(passphrase, salt, 210000, 32 bytes)
 * </pre>
 *
 * The iteration count is fixed. There is deliberately no way to ask for
 * fewer; keyring records that claim any other KDF are refused by
 * {@link SigilKeyring}.
 */
public final class SigilCircle {

    /** Must match {@code sigil.PBKDF2_ITERS}. Not configurable. */
    public static final int PBKDF2_ITERATIONS = 210_000;
    /** The only KDF string accepted in a keyring record. */
    public static final String KDF_ID = "PBKDF2-HMAC-SHA256/" + PBKDF2_ITERATIONS;

    private final String name;
    private final String slug;
    private final byte[] key;
    private final String fingerprint;

    private SigilCircle(String name, byte[] key) {
        this.name = name;
        this.slug = slugify(name, 4);
        this.key = key;
        this.fingerprint = fingerprintOf(key);
    }

    /**
     * Derive a circle key from its public name and secret passphrase. This is
     * slow on purpose (~210k PBKDF2 rounds): derive once and keep the object.
     */
    public static SigilCircle derive(String name, String passphrase) throws SigilException {
        if (name == null || name.isEmpty()) {
            throw new SigilException("Circle name is empty.");
        }
        if (passphrase == null || passphrase.isEmpty()) {
            throw new SigilException("Circle passphrase is empty.");
        }
        byte[] salt = Arrays.copyOf(sha256(("SIGIL.v1.circle.salt." + name).getBytes(StandardCharsets.UTF_8)), 16);
        char[] pw = passphrase.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(pw, salt, PBKDF2_ITERATIONS, 256);
        try {
            // SunJCE encodes the char[] password as UTF-8, matching passphrase.encode("utf-8").
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SigilCircle(name, key);
        } catch (GeneralSecurityException e) {
            throw new SigilException("PBKDF2WithHmacSHA256 unavailable.", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(pw, '\0');
        }
    }

    public String name() {
        return name;
    }

    /** Public four-character label used in tokens. */
    public String slug() {
        return slug;
    }

    /** Public four-character fingerprint: {@code b64url(sha256(key))[:4].lower()}. */
    public String fingerprint() {
        return fingerprint;
    }

    byte[] key() {
        return key;
    }

    /**
     * {@code sigil.slugify}: lowercase, keep letters/digits, pad with {@code x}.
     * Matches sigil for ASCII names; non-ASCII names may slug differently from
     * Python's Unicode {@code isalnum}, so keep circle names ASCII.
     */
    static String slugify(String name, int n) {
        StringBuilder b = new StringBuilder();
        String lower = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                b.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        if (b.length() == 0) {
            b.append("circ");
        }
        for (int i = 0; i < n; i++) {
            b.append('x');
        }
        return b.substring(0, n);
    }

    static String fingerprintOf(byte[] key) {
        return SigilB64.encode(sha256(key)).substring(0, 4).toLowerCase(Locale.ROOT);
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public String toString() {
        // Never print key material.
        return "SigilCircle{name=" + name + ", slug=" + slug + ", fp=" + fingerprint + "}";
    }
}
