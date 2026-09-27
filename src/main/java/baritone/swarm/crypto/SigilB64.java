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

import java.util.Base64;

/**
 * Unpadded URL-safe base64, as used by SIGIL ({@code A-Z a-z 0-9 - _}).
 */
final class SigilB64 {

    private SigilB64() {}

    static String encode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /** Strict decode (padding optional). Invalid characters are an error. */
    static byte[] decode(String text) throws SigilException {
        try {
            return Base64.getUrlDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new SigilException("Malformed base64url blob.");
        }
    }

    /** Length of {@code encode(new byte[n])} without allocating. */
    static int encodedLength(int n) {
        int full = (n / 3) * 4;
        int rem = n % 3;
        return full + (rem == 0 ? 0 : rem + 1);
    }
}
