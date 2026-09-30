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


package baritone.gui.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Merges GUI edits into the lines of {@code baritone/settings.txt} without rewriting anything else. That way
 * values forced at runtime (for example by TenorClef on startup) are not persisted just because the GUI saved.
 * The line format is Baritone's: {@code name value}; names are case-insensitive, comments start with # or //.
 */
public final class SettingsFileMerger {

    private SettingsFileMerger() {}

    /**
     * @param lines   existing file lines
     * @param updates lower-case setting name -> replacement line, or {@code null} to remove that setting's line
     *                (setting back at its default)
     * @return the new file lines
     */
    public static List<String> merge(List<String> lines, Map<String, String> updates) {
        Map<String, String> pending = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : updates.entrySet()) {
            pending.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        Set<String> written = new HashSet<>();
        List<String> out = new ArrayList<>(lines.size() + pending.size());
        for (String line : lines) {
            String key = keyOf(line);
            if (key == null || !pending.containsKey(key)) {
                out.add(line);
                continue;
            }
            if (written.add(key)) {
                String repl = pending.get(key);
                if (repl != null) {
                    out.add(repl);
                }
            }
            // later duplicate lines for the same setting are dropped (the last one would win on load otherwise)
        }
        for (Map.Entry<String, String> e : pending.entrySet()) {
            if (!written.contains(e.getKey()) && e.getValue() != null) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /** @return the lower-case setting name of a settings line, or {@code null} for blanks and comments */
    public static String keyOf(String line) {
        if (line == null || line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
            return null;
        }
        int sp = line.indexOf(' ');
        if (sp <= 0) {
            return null;
        }
        return line.substring(0, sp).toLowerCase(Locale.ROOT);
    }
}
