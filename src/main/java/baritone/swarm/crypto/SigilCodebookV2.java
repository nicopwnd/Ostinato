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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Exact port of {@code codebook.expand_v2} (sigil 0.4.0). Only expansion is
 * implemented: the Java sealer always sends raw UTF-8, so it never needs the
 * compressor.
 *
 * <p>The 4096-entry lexicon is a verbatim copy of sigil's {@code lexicon_v2.txt},
 * checked against its pinned SHA-256 when first used.
 */
public final class SigilCodebookV2 {

    public static final int MAGIC2 = 0xC2;
    public static final int WORD_COUNT = 4096;
    /** SHA-256 of sigil's {@code lexicon_v2.txt} (also recorded in the S2C vectors). */
    public static final String LEXICON_SHA256 = "46137094c869354a1c280884c80157d093f271e6bcab0a353516d5a524ec2278";
    static final String LEXICON_RESOURCE = "/baritone/swarm/crypto/sigil-lexicon-v2.txt";
    private static final String PUNCT = " .,:\n-/?!'\"();+";

    private static volatile String[] words;

    private SigilCodebookV2() {}

    static String[] words() throws SigilException {
        String[] w = words;
        if (w == null) {
            synchronized (SigilCodebookV2.class) {
                if (words == null) {
                    words = loadLexicon();
                }
                w = words;
            }
        }
        return w;
    }

    private static String[] loadLexicon() throws SigilException {
        byte[] raw;
        try (InputStream in = SigilCodebookV2.class.getResourceAsStream(LEXICON_RESOURCE)) {
            if (in == null) {
                throw new SigilException("Codebook v2 lexicon resource is missing.");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            raw = out.toByteArray();
        } catch (IOException e) {
            throw new SigilException("Could not read the codebook v2 lexicon.", e);
        }
        if (!LEXICON_SHA256.equals(sha256Hex(raw))) {
            throw new SigilException("Codebook v2 lexicon does not match the pinned sigil lexicon.");
        }
        List<String> out = new ArrayList<>(WORD_COUNT);
        for (String line : new String(raw, StandardCharsets.US_ASCII).split("\r\n|\r|\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        if (out.size() != WORD_COUNT) {
            throw new SigilException("Codebook v2 lexicon has " + out.size() + " entries, need " + WORD_COUNT + ".");
        }
        return out.toArray(new String[0]);
    }

    static String sha256Hex(byte[] data) {
        try {
            StringBuilder b = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(data)) {
                b.append(String.format("%02x", x & 0xFF));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** MSB-first bit reader, as {@code codebook._BitsIn}. */
    private static final class BitsIn {
        private final byte[] data;
        private int i = 1; // skip the magic byte
        private long acc;
        private int n;

        BitsIn(byte[] data) {
            this.data = data;
        }

        int read(int width) throws SigilException {
            while (n < width) {
                if (i >= data.length) {
                    throw new SigilException("Codebook v2: truncated bitstream.");
                }
                acc = (acc << 8) | (data[i++] & 0xFF);
                n += 8;
            }
            n -= width;
            int v = (int) ((acc >>> n) & ((1L << width) - 1));
            acc &= (1L << n) - 1;
            return v;
        }
    }

    /** {@code codebook.expand_v2}: decode a v2 stream (starting 0xC2) to text. */
    public static String expand(byte[] data) throws SigilException {
        if (data.length == 0 || (data[0] & 0xFF) != MAGIC2) {
            throw new SigilException("Not codebook v2.");
        }
        String[] w = words();
        BitsIn bits = new BitsIn(data);
        List<String> parts = new ArrayList<>();
        boolean[] needSpace = {false};
        while (true) {
            int tag = bits.read(3);
            switch (tag) {
                case 0:
                    push(parts, needSpace, w[bits.read(6)]);
                    break;
                case 1:
                    push(parts, needSpace, w[64 + bits.read(8)]);
                    break;
                case 2:
                    push(parts, needSpace, w[bits.read(12)]);
                    break;
                case 3:
                    push(parts, needSpace, Integer.toString(bits.read(6)));
                    break;
                case 4: {
                    int width = bits.read(5) + 1;
                    long zz = bits.read(width) & 0xFFFFFFFFL;
                    push(parts, needSpace, Long.toString((zz >> 1) ^ -(zz & 1)));
                    break;
                }
                case 5: {
                    int idx = bits.read(4);
                    if (idx >= PUNCT.length()) {
                        throw new SigilException("Codebook v2: bad punctuation index."); // Python IndexError
                    }
                    char ch = PUNCT.charAt(idx);
                    parts.add(String.valueOf(ch));
                    needSpace[0] = ".,:;?!".indexOf(ch) >= 0;
                    break;
                }
                case 6: {
                    int len = bits.read(4) + 1;
                    byte[] raw = new byte[len];
                    for (int k = 0; k < len; k++) {
                        raw[k] = (byte) bits.read(8);
                    }
                    String s = strictUtf8(raw);
                    if (pyIsAlnum(s.codePointAt(0))) {
                        push(parts, needSpace, s);
                    } else {
                        parts.add(s);
                        needSpace[0] = false;
                    }
                    break;
                }
                default: // 7: end
                    StringBuilder b = new StringBuilder();
                    for (String p : parts) {
                        b.append(p);
                    }
                    return b.toString();
            }
        }
    }

    private static void push(List<String> parts, boolean[] needSpace, String s) {
        if (needSpace[0] && !parts.isEmpty()) {
            String last = parts.get(parts.size() - 1);
            if (!last.endsWith(" ") && !last.endsWith("\n")) {
                parts.add(" ");
            }
        }
        parts.add(s);
        needSpace[0] = true;
    }

    /** Python {@code str.isalnum()} for one code point: letters (L*) or numerics (Nd, Nl, No). */
    static boolean pyIsAlnum(int cp) {
        if (Character.isLetter(cp)) {
            return true;
        }
        int t = Character.getType(cp);
        return t == Character.DECIMAL_DIGIT_NUMBER || t == Character.LETTER_NUMBER || t == Character.OTHER_NUMBER;
    }

    /** Strict UTF-8 decode, like Python's {@code bytes.decode("utf-8")}. */
    static String strictUtf8(byte[] raw) throws SigilException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            throw new SigilException("Invalid UTF-8.");
        }
    }
}
