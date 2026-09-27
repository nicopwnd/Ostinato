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

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * SIGIL protocol S1, circle mode ({@code S1C}), byte-compatible with {@code sigil.py}.
 *
 * <pre>
 * token = "S1C." slug ["." "z"] ["." i "/" n   (only when n &gt; 1)] "." b64url(nonce12 || ct || tag16)
 * AAD   = "SIGIL.v1.C." name [".z"] "." i "/" n
 * seal  = AES-256-GCM, 96-bit random nonce (SecureRandom), 128-bit tag
 * </pre>
 *
 * Scope: seal raw UTF-8 (no codebook), open raw and {@code .z} tokens. Opening
 * a {@code .z} token authenticates and returns the packed codebook bytes;
 * expanding codebook v2 is not implemented in Java. {@code S1K}/{@code S1E}
 * are not implemented. Nothing here is configurable: cipher, nonce size, tag
 * size, AAD and KDF are fixed by the protocol.
 */
public final class SigilS1C {

    public static final String VERSION = "S1";
    public static final String PROTOCOL = "SIGIL.v1";
    public static final int NONCE_LEN = 12;
    public static final int TAG_LEN = 16;
    /** Vanilla Minecraft chat send limit. */
    public static final int MINECRAFT_MAX_LINE = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SigilS1C() {}

    // ------------------------------------------------------------------ seal

    /**
     * Seal UTF-8 text for a circle, splitting into {@code i/n} parts exactly as
     * {@code sigil.seal_circle(..., compact=False)} does for the same {@code maxLine}.
     */
    public static List<String> seal(SigilCircle circle, String plaintext, int maxLine) throws SigilException {
        List<byte[]> nonces = new ArrayList<>();
        for (String ignored : chunkPlain(circle, plaintext, maxLine)) {
            byte[] n = new byte[NONCE_LEN];
            RANDOM.nextBytes(n);
            nonces.add(n);
        }
        return sealWithNonces(circle, plaintext, maxLine, nonces);
    }

    /** Seal as exactly one token, or fail if it would need more than one line. */
    public static String sealSingle(SigilCircle circle, String plaintext, int maxLine) throws SigilException {
        List<String> lines = seal(circle, plaintext, maxLine);
        if (lines.size() != 1) {
            throw new SigilException("Plaintext needs " + lines.size() + " lines at maxLine=" + maxLine + ".");
        }
        return lines.get(0);
    }

    /**
     * TEST HOOK (package-private): seal with caller-supplied nonces so the
     * output can be compared byte-for-byte with recorded sigil vectors. The
     * public API always draws nonces from {@link SecureRandom}.
     */
    static List<String> sealWithNonces(SigilCircle circle, String plaintext, int maxLine, List<byte[]> nonces)
            throws SigilException {
        List<String> parts = chunkPlain(circle, plaintext, maxLine);
        if (nonces.size() != parts.size()) {
            throw new SigilException("Need " + parts.size() + " nonces.");
        }
        List<String> lines = new ArrayList<>(parts.size());
        int total = parts.size();
        for (int i = 0; i < total; i++) {
            lines.add(sealPart(circle, parts.get(i).getBytes(StandardCharsets.UTF_8), false, i + 1, total, nonces.get(i)));
        }
        return lines;
    }

    /** TEST HOOK (package-private): one part with explicit payload bytes and nonce. */
    static String sealPart(SigilCircle circle, byte[] payload, boolean compact, int index, int total, byte[] nonce)
            throws SigilException {
        if (nonce.length != NONCE_LEN) {
            throw new SigilException("Nonce must be " + NONCE_LEN + " bytes.");
        }
        byte[] ct = gcm(Cipher.ENCRYPT_MODE, circle.key(), nonce, payload, aad(circle.name(), compact, index, total));
        byte[] blob = new byte[NONCE_LEN + ct.length];
        System.arraycopy(nonce, 0, blob, 0, NONCE_LEN);
        System.arraycopy(ct, 0, blob, NONCE_LEN, ct.length);
        StringBuilder b = new StringBuilder(VERSION).append("C.").append(circle.slug());
        if (compact) {
            b.append(".z");
        }
        if (total != 1) {
            b.append('.').append(index).append('/').append(total);
        }
        return b.append('.').append(SigilB64.encode(blob)).toString();
    }

    /** Port of {@code sigil._chunk_plain} with {@code seal_circle}'s room calculation. */
    static List<String> chunkPlain(SigilCircle circle, String text, int maxLine) {
        int header = 12 + circle.slug().length();
        int room = Math.max(32, maxLine - header);
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        if (SigilB64.encodedLength(NONCE_LEN + raw.length + TAG_LEN) <= room) {
            return Collections.singletonList(text);
        }
        int byteBudget = Math.max(24, (room * 3) / 4 - NONCE_LEN - TAG_LEN - 8);
        List<String> parts = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            byte[] piece = text.substring(i, i + len).getBytes(StandardCharsets.UTF_8);
            if (current.size() > 0 && current.size() + piece.length > byteBudget) {
                parts.add(new String(current.toByteArray(), StandardCharsets.UTF_8));
                current.reset();
            }
            current.write(piece, 0, piece.length);
            i += len;
        }
        if (current.size() > 0) {
            parts.add(new String(current.toByteArray(), StandardCharsets.UTF_8));
        }
        if (parts.isEmpty()) {
            parts.add("");
        }
        return parts;
    }

    // ------------------------------------------------------------------ open

    /** Result of opening one S1C line. */
    public static final class Opened {
        private final SigilCircle circle;
        private final int index;
        private final int total;
        private final boolean headerCompact;
        private final boolean payloadCompact;
        private final byte[] payload;

        Opened(SigilCircle circle, int index, int total, boolean headerCompact, boolean payloadCompact, byte[] payload) {
            this.circle = circle;
            this.index = index;
            this.total = total;
            this.headerCompact = headerCompact;
            this.payloadCompact = payloadCompact;
            this.payload = payload;
        }

        public SigilCircle circle() { return circle; }
        public int index() { return index; }
        public int total() { return total; }
        /** Whether the token carried a {@code .z} flag. */
        public boolean headerCompact() { return headerCompact; }
        /** Whether the payload authenticated under the {@code .z} AAD (i.e. is codebook-packed). */
        public boolean payloadCompact() { return payloadCompact; }
        /** Exact authenticated bytes (UTF-8, or packed codebook when {@link #payloadCompact()}). */
        public byte[] payload() { return payload.clone(); }

        /** UTF-8 plaintext. Codebook v2 expansion is not implemented in Java. */
        public String text() throws SigilException {
            if (payloadCompact) {
                throw new SigilException("Codebook (.z) payload: expansion not implemented in the Java codec.");
            }
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    /**
     * Open one chat line against a keyring, mirroring {@code sigil.open_line}
     * for circle tokens: the token may be buried in other text; circles whose
     * slug (or lowercased name) matches are tried in order and the GCM tag
     * picks the winner; a {@code .z} token that fails under the compact AAD is
     * retried under the raw AAD, as sigil does.
     */
    public static Opened open(String line, Collection<SigilCircle> keyring) throws SigilException {
        if (line == null) {
            throw new SigilException("Not a SIGIL S1 message.");
        }
        String raw = line.trim();
        for (String tok : raw.replace(',', ' ').split("\\s+")) {
            if (tok.startsWith("S1C.") || tok.startsWith("S1K.") || tok.startsWith("S1E.")) {
                raw = tok;
                break;
            }
        }
        String[] parts = raw.split("\\.", -1);
        if (parts.length < 3 || !parts[0].startsWith(VERSION)) {
            throw new SigilException("Not a SIGIL S1 message.");
        }
        String kind = parts[0].substring(2);
        String blob = parts[parts.length - 1];
        int lo = 1;
        int hi = parts.length - 1; // exclusive
        int index = 1;
        int total = 1;
        if (hi > lo && isFragmentField(parts[hi - 1])) {
            String f = parts[hi - 1];
            int slash = f.indexOf('/');
            try {
                index = Integer.parseInt(f.substring(0, slash));
                total = Integer.parseInt(f.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new SigilException("Malformed fragment field.");
            }
            hi--;
        }
        boolean compact = false;
        if (hi > lo && parts[hi - 1].equals("z")) {
            compact = true;
            hi--;
        }
        if (!kind.equals("C")) {
            if (kind.equals("K") || kind.equals("E")) {
                throw new SigilException("S1" + kind + " is not implemented in the Java codec.");
            }
            throw new SigilException("Unknown SIGIL kind '" + kind + "'.");
        }
        if (hi <= lo) {
            throw new SigilException("Circle message missing slug.");
        }
        String slug = parts[lo];
        byte[] data = SigilB64.decode(blob);
        for (SigilCircle circle : keyring) {
            if (!circle.slug().equals(slug) && !circle.name().toLowerCase(Locale.ROOT).equals(slug)) {
                continue;
            }
            byte[] pt = tryOpen(circle, data, index, total, compact);
            boolean payloadCompact = compact;
            if (pt == null && compact) {
                pt = tryOpen(circle, data, index, total, false);
                payloadCompact = false;
            }
            if (pt != null) {
                return new Opened(circle, index, total, compact, payloadCompact, pt);
            }
        }
        throw new SigilException("Could not open circle message for slug '" + slug + "'. Wrong circle or passphrase.");
    }

    /** {@code "/" in f and f.replace("/", "").isdigit()} (ASCII digits). */
    private static boolean isFragmentField(String f) {
        if (f.indexOf('/') < 0) {
            return false;
        }
        String digits = f.replace("/", "");
        if (digits.isEmpty()) {
            return false;
        }
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static byte[] tryOpen(SigilCircle circle, byte[] data, int index, int total, boolean compact)
            throws SigilException {
        if (data.length < NONCE_LEN + TAG_LEN) {
            return null;
        }
        byte[] nonce = new byte[NONCE_LEN];
        System.arraycopy(data, 0, nonce, 0, NONCE_LEN);
        byte[] ct = new byte[data.length - NONCE_LEN];
        System.arraycopy(data, NONCE_LEN, ct, 0, ct.length);
        try {
            return gcm(Cipher.DECRYPT_MODE, circle.key(), nonce, ct, aad(circle.name(), compact, index, total));
        } catch (AuthFailure e) {
            return null;
        }
    }

    static byte[] aad(String name, boolean compact, int index, int total) {
        return (PROTOCOL + ".C." + name + (compact ? ".z" : "") + "." + index + "/" + total)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static final class AuthFailure extends SigilException {
        AuthFailure() {
            super("GCM authentication failed.");
        }
    }

    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] input, byte[] aad) throws SigilException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LEN * 8, nonce));
            c.updateAAD(aad);
            return c.doFinal(input);
        } catch (AEADBadTagException e) {
            throw new AuthFailure();
        } catch (GeneralSecurityException e) {
            if (mode == Cipher.DECRYPT_MODE) {
                throw new AuthFailure();
            }
            throw new SigilException("AES/GCM/NoPadding unavailable.", e);
        }
    }
}
