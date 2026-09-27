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


package baritone.swarm;

import baritone.swarm.crypto.SigilCircle;

/** Public test-only circles (PBKDF2 is slow, so derive once per JVM). Never real keys. */
public final class TestCircles {

    private static SigilCircle alpha;
    private static SigilCircle beta;
    private static SigilCircle alphaWrongPass;

    private TestCircles() {}

    public static synchronized SigilCircle alpha() throws Exception {
        if (alpha == null) {
            alpha = SigilCircle.derive("swarm-test-alpha", "public-test-passphrase-DO-NOT-USE");
        }
        return alpha;
    }

    public static synchronized SigilCircle beta() throws Exception {
        if (beta == null) {
            beta = SigilCircle.derive("swarm-test-beta", "public-test-passphrase-DO-NOT-USE");
        }
        return beta;
    }

    /** Same name (same slug) as {@link #alpha()}, different passphrase. */
    public static synchronized SigilCircle alphaWrongPass() throws Exception {
        if (alphaWrongPass == null) {
            alphaWrongPass = SigilCircle.derive("swarm-test-alpha", "some-other-public-test-passphrase");
        }
        return alphaWrongPass;
    }
}
