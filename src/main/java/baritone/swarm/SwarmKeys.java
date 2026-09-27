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
import baritone.swarm.crypto.SigilException;
import baritone.swarm.crypto.SigilKeyring;
import baritone.swarm.roster.SwarmRoster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves each roster group this member belongs to into its sigil circle, read from the sigil
 * keyring ({@code circle-*.json}). The keyring stays where sigil keeps it; nothing is copied.
 */
public final class SwarmKeys {

    private SwarmKeys() {}

    /** {@code swarmSigilHome} if set, else {@code $SIGIL_HOME}; {@code null} if neither is set. */
    public static Path sigilHome(String setting, String envSigilHome) {
        if (setting != null && !setting.trim().isEmpty()) {
            return Paths.get(setting.trim());
        }
        if (envSigilHome != null && !envSigilHome.trim().isEmpty()) {
            return Paths.get(envSigilHome.trim());
        }
        return null;
    }

    /**
     * @return group id to circle for every group {@code self} is a member of
     * @throws SigilException if the home is missing, a needed circle is absent, or a record is bad
     */
    public static Map<String, SigilCircle> circlesFor(SwarmRoster roster, String self, Path home)
            throws SigilException {
        if (home == null) {
            throw new SigilException("No sigil home: set swarmSigilHome or SIGIL_HOME.");
        }
        if (!Files.isDirectory(home)) {
            throw new SigilException("Sigil home " + home + " is not a directory.");
        }
        List<SwarmRoster.Group> mine = roster.groupsOf(self);
        if (mine.isEmpty()) {
            throw new SigilException("You (" + self + ") are not a member of any roster group.");
        }
        Set<String> wanted = new HashSet<>();
        for (SwarmRoster.Group g : mine) {
            wanted.add(g.circle());
        }
        Map<String, SigilCircle> byName = new HashMap<>();
        for (SigilCircle c : SigilKeyring.loadCircles(home, wanted::contains)) {
            if (byName.put(c.name(), c) != null) {
                throw new SigilException("Keyring has two records for circle '" + c.name() + "'.");
            }
        }
        Map<String, SigilCircle> out = new LinkedHashMap<>();
        for (SwarmRoster.Group g : mine) {
            SigilCircle c = byName.get(g.circle());
            if (c == null) {
                throw new SigilException("Circle '" + g.circle() + "' for group '" + g.id() + "' is not in the keyring.");
            }
            out.put(g.id(), c);
        }
        return out;
    }
}
