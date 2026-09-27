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

import java.util.Locale;

/** Which SIGIL wire format to emit. S1 and S2C stay accepted when opening. */
public enum SigilWire {
    /** {@code S1C.<slug>[.z][.i/n].<blob>} (sigil 0.3 and older). */
    S1,
    /** {@code S2C.<slug>.<blob>}: sigil's default since 0.4.0. */
    S2,
    /** {@code S2S.<slug>.<blob>}: signed circle, sigil 0.5.0. */
    S2S;

    public static final SigilWire DEFAULT = S2;

    public static SigilWire parse(String s) {
        if (s != null) {
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (t.equals("S1")) {
                return S1;
            }
            if (t.equals("S2")) {
                return S2;
            }
            if (t.equals("S2S")) {
                return S2S;
            }
        }
        return DEFAULT;
    }
}
