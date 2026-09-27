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

/** Wire-version-neutral entry: seal S1/S2C; open S1/S2C; extract S2S tokens too. */
public final class SigilCodec {

    private static final String[] TOKEN_HEADS = {
            "S1C.", "S1K.", "S1E.", "S2C.", "S2K.", "S2E.", "S2S."
    };

    private SigilCodec() {}

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

    public static String sealSingle(SigilWire wire, SigilCircle circle, String plaintext, int maxLine)
            throws SigilException {
        if (wire == SigilWire.S1) {
            return SigilS1C.sealSingle(circle, plaintext, maxLine);
        }
        if (wire == SigilWire.S2S) {
            throw new SigilException("S2S seal needs a signet; use SigilS2S.sealSingle.");
        }
        return SigilS2C.sealSingle(circle, plaintext, maxLine);
    }

    public static int maxSingleLinePayloadBytes(SigilWire wire, int maxLine) {
        if (wire == SigilWire.S1) {
            return SigilS1C.maxSingleLinePayloadBytes(maxLine);
        }
        if (wire == SigilWire.S2S) {
            return SigilS2S.maxSingleLinePayloadBytes(maxLine);
        }
        return SigilS2C.maxSingleLinePayloadBytes(maxLine);
    }

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
        public String sender() { return sender; }
    }

    public static Opened open(String line, Collection<SigilCircle> keyring) throws SigilException {
        String token = extractToken(line);
        if (token.startsWith(SigilS2S.VERSION + ".")) {
            throw new SigilException("S2S open needs pinned signets; use SigilS2S.open.");
        }
        if (token.startsWith(SigilS2C.VERSION)) {
            SigilS2C.Opened o = SigilS2C.open(token, keyring);
            return new Opened(SigilWire.S2, o.circle(), o.index(), o.total(), o.text(), o.sender());
        }
        SigilS1C.Opened o = SigilS1C.open(token, keyring);
        return new Opened(SigilWire.S1, o.circle(), o.index(), o.total(), o.text(), null);
    }
}
