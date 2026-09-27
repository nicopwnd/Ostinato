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

import java.util.Collection;

/**
 * Wire-version-neutral entry point: seal with a chosen {@link SigilWire},
 * open either version (like {@code sigil.open_line}, which picks the first
 * S1/S2 token on the line).
 */
public final class SigilCodec {

    private static final String[] TOKEN_HEADS = {"S1C.", "S1K.", "S1E.", "S2C.", "S2K.", "S2E."};

    private SigilCodec() {}

    /** First whitespace/comma-separated token that starts like a SIGIL token, else the trimmed line. */
    static String extractToken(String line) throws SigilException {
        if (line == null) {
            throw new SigilException("Not a SIGIL message.");
        }
        String raw = line.trim();
        for (String tok : raw.replace(',', ' ').split("\\s+")) {
            for (String head : TOKEN_HEADS) {
                if (tok.startsWith(head)) {
                    return tok;
                }
            }
        }
        return raw;
    }

    /** Seal as exactly one line in the given wire format (no sender field), or fail. */
    public static String sealSingle(SigilWire wire, SigilCircle circle, String plaintext, int maxLine)
            throws SigilException {
        return wire == SigilWire.S1
                ? SigilS1C.sealSingle(circle, plaintext, maxLine)
                : SigilS2C.sealSingle(circle, plaintext, maxLine);
    }

    /** Largest UTF-8 plaintext {@link #sealSingle} fits into one line of {@code maxLine}. */
    public static int maxSingleLinePayloadBytes(SigilWire wire, int maxLine) {
        return wire == SigilWire.S1
                ? SigilS1C.maxSingleLinePayloadBytes(maxLine)
                : SigilS2C.maxSingleLinePayloadBytes(maxLine);
    }

    /** Result of opening one circle line of either wire version. */
    public static final class Opened {
        private final SigilWire wire;
        private final SigilCircle circle;
        private final int index;
        private final int total;
        private final String text;
        private final String sender;

        Opened(SigilWire wire, SigilCircle circle, int index, int total, String text, String sender) {
            this.wire = wire;
            this.circle = circle;
            this.index = index;
            this.total = total;
            this.text = text;
            this.sender = sender;
        }

        public SigilWire wire() { return wire; }
        public SigilCircle circle() { return circle; }
        public int index() { return index; }
        public int total() { return total; }
        public String text() { return text; }
        /** S2 sealed sender (part 1), else {@code null}. */
        public String sender() { return sender; }
    }

    /**
     * Open one line of either wire version. S1 codebook ({@code .z}) payloads
     * are still refused, as before; S2 codebook parts are expanded.
     */
    public static Opened open(String line, Collection<SigilCircle> keyring) throws SigilException {
        String token = extractToken(line);
        if (token.startsWith(SigilS2C.VERSION)) {
            SigilS2C.Opened o = SigilS2C.open(token, keyring);
            return new Opened(SigilWire.S2, o.circle(), o.index(), o.total(), o.text(), o.sender());
        }
        SigilS1C.Opened o = SigilS1C.open(token, keyring);
        return new Opened(SigilWire.S1, o.circle(), o.index(), o.total(), o.text(), null);
    }
}
