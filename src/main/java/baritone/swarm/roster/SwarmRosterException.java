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


package baritone.swarm.roster;

/** The roster file is invalid. {@link #line()} is 1-based, or 0 for whole-file checks. */
public final class SwarmRosterException extends Exception {

    private final int line;

    public SwarmRosterException(int line, String message) {
        super((line > 0 ? "swarm roster line " + line + ": " : "swarm roster: ") + message);
        this.line = line;
    }

    public int line() {
        return line;
    }
}
