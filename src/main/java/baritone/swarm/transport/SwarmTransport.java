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


package baritone.swarm.transport;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Moves sealed swarm lines between members. Transports only ever carry
 * single-part S1C tokens; there is no plaintext mode, and {@link #requireSealed}
 * refuses anything else before it leaves the process.
 */
public interface SwarmTransport extends Closeable {

    /** Recipient meaning "every other member". */
    String BROADCAST = "*";

    /** Single-part, non-codebook S1C token: {@code S1C.<slug>.<base64url>}. */
    Pattern SEALED_LINE = Pattern.compile("S1C\\.[a-z0-9]{4}\\.[A-Za-z0-9_-]{40,}");

    /** This member's id on the transport. */
    String selfId();

    /** Send one sealed line to {@code recipient} or {@link #BROADCAST}. */
    void send(String recipient, String sealedLine) throws IOException;

    /** Drain lines received since the last call (possibly empty, any order). */
    List<String> receive() throws IOException;

    static boolean isSealed(String line) {
        return line != null && SEALED_LINE.matcher(line).matches();
    }

    static void requireSealed(String line) {
        if (!isSealed(line)) {
            throw new IllegalArgumentException("swarm transports only carry single S1C tokens");
        }
    }
}
