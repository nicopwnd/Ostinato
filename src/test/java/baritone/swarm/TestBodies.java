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

/** Deterministic multi-byte test bodies. */
final class TestBodies {

    private TestBodies() {}

    static String make(int chars) {
        String alphabet = "abc|\u00e9\u4e16 xyz0123456789-";
        StringBuilder b = new StringBuilder();
        for (int i = 0; b.length() < chars; i++) {
            b.append(alphabet.charAt(i % alphabet.length()));
        }
        return b.toString();
    }
}
