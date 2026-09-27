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

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * SIGIL S2 circle messages ({@code S2C.<slug>.<blob>}), ported from sigil 0.4.0
 * ({@code seal_circle_s2}, {@code open_line_s2}, {@code s2_parse_frame},
 * {@code s2_split} and {@code assemble_messages}; README "Wire format S2").
 *
 * <pre>
 * frame  = header nonce12 ciphertext||tag16
 * header = flags [part mid6]            part/mid iff M; part = (i-1)&lt;&lt;4 | (n-1)
 * flags  = Z 0x01 | J 0x02 | M 0x04 | S 0x08, 0xF0 reserved (must be 0)
 * aad    = header || UTF-8("SIGIL.v2.C." + name)     key = the S1 circle key
 * pt     = [slen sender] body            body = codebook v2 (iff Z) | UTF-8
 * </pre>
 *
 * Opening is complete, including codebook v2 ({@code Z}) parts. Sealing emits
 * raw UTF-8 only (never {@code Z}), which every S2 reader opens. {@code S2K}
 * and {@code S2E} (P-256 ECDH + HKDF) are recognised but not implemented.
 */
public final class SigilS2C {

    public static final String VERSION = "S2";
    public static final String PROTOCOL = "SIGIL.v2";
    public static final int FLAG_Z = 0x01;
    public static final int FLAG_J = 0x02;
    public static final int FLAG_M = 0x04;
    public static final int FLAG_S = 0x08;
    public static final int FLAG_RESERVED = 0xF0;
    public static final int MID_LEN = 6;
    public static final int MAX_PARTS = 16;
    public static final int MAX_SENDER = 32;
    static final int NONCE_LEN = SigilS1C.NONCE_LEN;
    static final int TAG_LEN = SigilS1C.TAG_LEN;
    /** {@code "S2C." + slug + "."}; slugs are always 4 characters. */
    static final int C_PREFIX_LEN = 9;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SigilS2C() {}

    // ------------------------------------------------------------------ sizes

    /** {@code sigil._b64_room}: largest byte count whose unpadded base64url fits {@code chars}. */
    static int b64Room(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return (chars / 4) * 3 + Math.max(0, chars % 4 - 1);
    }

    /** {@code sigil.s2_payload_room} for mode C: plaintext bytes (sender field + body) per line. */
    static int payloadRoom(int maxLine, boolean multi) {
        int header = multi ? 2 + MID_LEN : 1;
        return b64Room(maxLine - C_PREFIX_LEN) - header - NONCE_LEN - TAG_LEN;
    }

    /** Largest raw UTF-8 body (no sender) that {@link #sealSingle} fits into one line of {@code maxLine}. */
    public static int maxSingleLinePayloadBytes(int maxLine) {
        return Math.max(0, payloadRoom(maxLine, false));
    }

    // ------------------------------------------------------------------ seal

    /** Seal raw UTF-8 text (optional sender in part 1), split exactly as {@code seal_circle_s2(compact=False)}. */
    public static List<String> seal(SigilCircle circle, String plaintext, String sender, int maxLine)
            throws SigilException {
        List<String> parts = split(plaintext, sender, maxLine);
        byte[] mid = new byte[parts.size() > 1 ? MID_LEN : 0];
        RANDOM.nextBytes(mid);
        List<byte[]> nonces = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            byte[] n = new byte[NONCE_LEN];
            RANDOM.nextBytes(n);
            nonces.add(n);
        }
        return sealWithRandom(circle, plaintext, sender, maxLine, mid, nonces);
    }

    /** Seal as exactly one token (no sender), or fail if it needs more than one line. */
    public static String sealSingle(SigilCircle circle, String plaintext, int maxLine) throws SigilException {
        List<String> lines = seal(circle, plaintext, "", maxLine);
        if (lines.size() != 1) {
            throw new SigilException("Plaintext needs " + lines.size() + " lines at maxLine=" + maxLine + ".");
        }
        return lines.get(0);
    }

    /** TEST HOOK: seal with a caller-supplied message id and nonces (to compare with recorded vectors). */
    static List<String> sealWithRandom(SigilCircle circle, String plaintext, String sender, int maxLine,
                                       byte[] mid, List<byte[]> nonces) throws SigilException {
        List<String> parts = split(plaintext, sender, maxLine);
        int total = parts.size();
        if (nonces.size() != total || mid.length != (total > 1 ? MID_LEN : 0)) {
            throw new SigilException("Need " + total + " nonces and a " + (total > 1 ? MID_LEN : 0) + "-byte id.");
        }
        byte[] sfield = senderField(sender);
        List<String> lines = new ArrayList<>(total);
        for (int i = 1; i <= total; i++) {
            int flags = 0;
            byte[] body = parts.get(i - 1).getBytes(StandardCharsets.UTF_8);
            byte[] pt = body;
            if (i == 1 && sfield.length > 0) {
                flags |= FLAG_S;
                pt = concat(sfield, body);
            }
            String line = sealFrame(circle, header(flags, i, total, mid), pt, nonces.get(i - 1));
            if (line.length() > maxLine) { // budgets are exact; this is a guard, as in sigil
                throw new IllegalStateException("S2 line " + line.length() + " > max_line " + maxLine);
            }
            lines.add(line);
        }
        return lines;
    }

    /** TEST HOOK: one token from explicit header bytes, plaintext and nonce. */
    static String sealFrame(SigilCircle circle, byte[] header, byte[] plaintext, byte[] nonce) throws SigilException {
        if (nonce.length != NONCE_LEN) {
            throw new SigilException("Nonce must be " + NONCE_LEN + " bytes.");
        }
        byte[] ct = SigilS1C.gcm(Cipher.ENCRYPT_MODE, circle.key(), nonce, plaintext, aad(header, circle.name()));
        return VERSION + "C." + circle.slug() + "." + SigilB64.encode(concat(concat(header, nonce), ct));
    }

    /** {@code sigil.s2_header}. */
    static byte[] header(int flags, int index, int total, byte[] mid) throws SigilException {
        if ((flags & (FLAG_RESERVED | FLAG_M)) != 0) {
            throw new SigilException("s2_header: flags must not carry reserved or M bits");
        }
        if (total == 1) {
            if (index != 1 || mid.length != 0) {
                throw new SigilException("single-part S2 frame has no part field or message id");
            }
            return new byte[]{(byte) flags};
        }
        if (total < 2 || total > MAX_PARTS || index < 1 || index > total || mid.length != MID_LEN) {
            throw new SigilException("bad S2 part field");
        }
        byte[] h = new byte[2 + MID_LEN];
        h[0] = (byte) (flags | FLAG_M);
        h[1] = (byte) (((index - 1) << 4) | (total - 1));
        System.arraycopy(mid, 0, h, 2, MID_LEN);
        return h;
    }

    static byte[] senderField(String sender) throws SigilException {
        if (sender == null || sender.isEmpty()) {
            return new byte[0];
        }
        byte[] raw = sender.getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_SENDER) {
            throw new SigilException("Sender name is longer than " + MAX_SENDER + " UTF-8 bytes.");
        }
        byte[] f = new byte[1 + raw.length];
        f[0] = (byte) raw.length;
        System.arraycopy(raw, 0, f, 1, raw.length);
        return f;
    }

    /**
     * {@code sigil.s2_split} with {@code compact=False}: cut text (by code
     * points, as Python slices) into parts whose UTF-8 fits each line. Raw
     * parts never consume a space, so every {@code J} is 0.
     */
    static List<String> split(String text, String sender, int maxLine) throws SigilException {
        int sfield = senderField(sender).length;
        int single = payloadRoom(maxLine, false);
        int[] cps = text.codePoints().toArray();
        long[] prefix = new long[cps.length + 1]; // UTF-8 bytes of cps[0..k)
        for (int k = 0; k < cps.length; k++) {
            prefix[k + 1] = prefix[k] + utf8Len(cps[k]);
        }
        if (sfield + prefix[cps.length] <= single) {
            return Collections.singletonList(text);
        }
        int room = payloadRoom(maxLine, true);
        if (room - sfield < 8) {
            throw new SigilException("max_line is too small for an S2 fragment.");
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < cps.length) {
            int budget = room - (parts.isEmpty() ? sfield : 0);
            int restLen = cps.length - start;
            int span = (int) Math.min(restLen, (long) budget * 12);
            if (span == restLen && prefix[cps.length] - prefix[start] <= budget) {
                parts.add(new String(cps, start, restLen));
                break;
            }
            int lo = 1;
            int hi = span;
            int best = 0;
            while (lo <= hi) {
                int m = (lo + hi) >>> 1;
                if (prefix[start + m] - prefix[start] <= budget) {
                    best = m;
                    lo = m + 1;
                } else {
                    hi = m - 1;
                }
            }
            if (best == 0) {
                throw new SigilException("max_line is too small for an S2 fragment.");
            }
            parts.add(new String(cps, start, best));
            start += best;
        }
        if (parts.size() > MAX_PARTS) {
            throw new SigilException("S2 carries at most " + MAX_PARTS + " parts; this message needs "
                    + parts.size() + ". Shorten it, raise max_line, or use wire S1.");
        }
        return parts;
    }

    private static int utf8Len(int cp) {
        return cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
    }

    static byte[] aad(byte[] header, String circleName) {
        return concat(header, (PROTOCOL + ".C." + circleName).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ------------------------------------------------------------------ frame

    /** Parsed header fields of an S2 frame (mode C/K: no ephemeral key). */
    static final class Frame {
        final int flags;
        final int index;
        final int total;
        final byte[] mid;
        final byte[] header;
        final byte[] nonce;
        final byte[] ct;

        Frame(int flags, int index, int total, byte[] mid, byte[] header, byte[] nonce, byte[] ct) {
            this.flags = flags;
            this.index = index;
            this.total = total;
            this.mid = mid;
            this.header = header;
            this.nonce = nonce;
            this.ct = ct;
        }
    }

    /** {@code sigil.s2_parse_frame}: every structural check happens here, before decryption. */
    static Frame parseFrame(byte[] data, boolean ephemeral) throws SigilException {
        if (data.length == 0) {
            throw new SigilException("Empty S2 blob.");
        }
        int flags = data[0] & 0xFF;
        if ((flags & FLAG_RESERVED) != 0) {
            throw new SigilException("S2 header sets reserved bits (newer format?).");
        }
        int pos = 1;
        int index = 1;
        int total = 1;
        byte[] mid = new byte[0];
        if ((flags & FLAG_M) != 0) {
            if (data.length < 2 + MID_LEN) {
                throw new SigilException("S2 blob truncated.");
            }
            index = ((data[1] & 0xFF) >> 4) + 1;
            total = (data[1] & 0x0F) + 1;
            if (total < 2 || index > total) {
                throw new SigilException("Bad S2 part field.");
            }
            mid = Arrays.copyOfRange(data, 2, 2 + MID_LEN);
            pos = 2 + MID_LEN;
        }
        if ((flags & FLAG_J) != 0 && index == total) {
            throw new SigilException("S2 join flag set on the last part.");
        }
        if ((flags & FLAG_S) != 0 && index != 1) {
            throw new SigilException("S2 sender flag set on a part other than 1.");
        }
        byte[] header = Arrays.copyOfRange(data, 0, pos);
        int eph = ephemeral ? 33 : 0;
        if (data.length < pos + eph + NONCE_LEN + TAG_LEN) {
            throw new SigilException("S2 blob truncated.");
        }
        pos += eph;
        byte[] nonce = Arrays.copyOfRange(data, pos, pos + NONCE_LEN);
        byte[] ct = Arrays.copyOfRange(data, pos + NONCE_LEN, data.length);
        return new Frame(flags, index, total, mid, header, nonce, ct);
    }

    // ------------------------------------------------------------------ open

    /** One opened S2C line. */
    public static final class Opened {
        private final SigilCircle circle;
        private final Frame frame;
        private final byte[] plaintext;
        private final String sender;
        private final String text;

        Opened(SigilCircle circle, Frame frame, byte[] plaintext, String sender, String text) {
            this.circle = circle;
            this.frame = frame;
            this.plaintext = plaintext;
            this.sender = sender;
            this.text = text;
        }

        public SigilCircle circle() { return circle; }
        public int index() { return frame.index; }
        public int total() { return frame.total; }
        public int flags() { return frame.flags; }
        /** 6-byte message id, or empty for single-line messages. */
        public byte[] mid() { return frame.mid.clone(); }
        public String midHex() { return hex(frame.mid); }
        public byte[] header() { return frame.header.clone(); }
        public byte[] nonce() { return frame.nonce.clone(); }
        /** Exact authenticated plaintext bytes (sender field + body). */
        public byte[] plaintext() { return plaintext.clone(); }
        /** Whether exactly one space follows this part when rejoining. */
        public boolean join() { return (frame.flags & FLAG_J) != 0; }
        public boolean codebook() { return (frame.flags & FLAG_Z) != 0; }
        /** Sealed sender name (part 1 only), or {@code null}. Proves circle membership only. */
        public String sender() { return sender; }
        /** This part's text, codebook-expanded if {@code Z}. */
        public String text() { return text; }
    }

    /**
     * Open one line holding an S2 token (bare or buried in text, like
     * {@code sigil.open_line}). Circles whose slug or lowercased name match are
     * tried in order; the GCM tag picks the winner.
     */
    public static Opened open(String line, Collection<SigilCircle> keyring) throws SigilException {
        String token = SigilCodec.extractToken(line);
        String[] fields = token.split("\\.", -1);
        String kind = fields[0].length() >= 2 ? fields[0].substring(2) : "";
        if (!fields[0].startsWith(VERSION) || !(kind.equals("C") || kind.equals("K") || kind.equals("E"))
                || fields.length < 3) {
            throw new SigilException("Not a SIGIL S2 message.");
        }
        byte[] data = SigilB64.decode(fields[fields.length - 1]);
        Frame fr = parseFrame(data, kind.equals("E"));
        if (!kind.equals("C")) {
            throw new SigilException("S2" + kind + " is not implemented in the Java codec.");
        }
        if (fields.length != 3) {
            throw new SigilException("S2 circle token needs exactly one slug.");
        }
        String slug = fields[1];
        for (SigilCircle circle : keyring) {
            if (!circle.slug().equals(slug) && !circle.name().toLowerCase(Locale.ROOT).equals(slug)) {
                continue;
            }
            byte[] pt;
            try {
                pt = SigilS1C.gcm(Cipher.DECRYPT_MODE, circle.key(), fr.nonce, fr.ct, aad(fr.header, circle.name()));
            } catch (SigilS1C.AuthFailure e) {
                continue;
            }
            return unpack(circle, fr, pt);
        }
        throw new SigilException("Could not open circle message for slug '" + slug + "'. Wrong circle or passphrase.");
    }

    /** {@code sigil.s2_unpack_plaintext}. */
    private static Opened unpack(SigilCircle circle, Frame fr, byte[] pt) throws SigilException {
        String sender = null;
        byte[] body = pt;
        if ((fr.flags & FLAG_S) != 0) {
            int n = pt.length > 0 ? pt[0] & 0xFF : 0;
            if (n < 1 || n > MAX_SENDER || pt.length < 1 + n) {
                throw new SigilException("Bad S2 sender field.");
            }
            sender = SigilCodebookV2.strictUtf8(Arrays.copyOfRange(pt, 1, 1 + n));
            body = Arrays.copyOfRange(pt, 1 + n, pt.length);
        }
        String text;
        if ((fr.flags & FLAG_Z) != 0) {
            if (body.length == 0 || (body[0] & 0xFF) != SigilCodebookV2.MAGIC2) {
                throw new SigilException("S2 codebook body does not start with the v2 magic byte.");
            }
            text = SigilCodebookV2.expand(body);
        } else {
            text = SigilCodebookV2.strictUtf8(body);
        }
        return new Opened(circle, fr, pt, sender, text);
    }

    // ------------------------------------------------------------------ rejoin

    /** A group of opened parts sharing (circle, message id, n). */
    public static final class Message {
        private final int total;
        private final TreeMap<Integer, Opened> parts;

        Message(int total, TreeMap<Integer, Opened> parts) {
            this.total = total;
            this.parts = parts;
        }

        public int total() { return total; }
        public boolean complete() { return parts.size() == total; }
        public List<Integer> missing() {
            List<Integer> m = new ArrayList<>();
            for (int i = 1; i <= total; i++) {
                if (!parts.containsKey(i)) {
                    m.add(i);
                }
            }
            return m;
        }
        /** Sender from part 1, or {@code null}. */
        public String sender() {
            Opened first = parts.get(1);
            return first == null ? null : first.sender();
        }
        /** {@code text_1 + (" " if J_1) + ... + text_n}, or {@code null} while incomplete. */
        public String text() {
            if (!complete()) {
                return null;
            }
            StringBuilder b = new StringBuilder();
            for (Opened o : parts.values()) {
                b.append(o.text());
                if (o.join()) {
                    b.append(' ');
                }
            }
            return b.toString();
        }
    }

    /**
     * {@code sigil.assemble_messages} for S2C parts: group by (circle, message
     * id, n) in first-seen order; the first copy of each index wins, so a
     * replayed duplicate is ignored. Parts of different messages never combine.
     */
    public static List<Message> assemble(List<Opened> opened) {
        Map<String, Message> groups = new LinkedHashMap<>();
        for (Opened o : opened) {
            String key = o.circle().name() + '\u0000' + o.midHex() + '\u0000' + o.total();
            Message m = groups.get(key);
            if (m == null) {
                m = new Message(o.total(), new TreeMap<>());
                groups.put(key, m);
            }
            m.parts.putIfAbsent(o.index(), o);
        }
        return new ArrayList<>(groups.values());
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format("%02x", x & 0xFF));
        }
        return s.toString();
    }
}
