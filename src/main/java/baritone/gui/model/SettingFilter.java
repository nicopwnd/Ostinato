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

import java.util.Locale;

/**
 * Search matching for the settings screen. Every whitespace-separated token of the query must appear in the
 * setting name (raw or split on camelCase) or in its description. Case-insensitive.
 */
public final class SettingFilter {

    private SettingFilter() {}

    /** "allowParkourPlace" -> "allow parkour place" */
    public static String humanize(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 8);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(camel.charAt(i - 1))) {
                sb.append(' ');
            } else if (i > 0 && Character.isDigit(c) && !Character.isDigit(camel.charAt(i - 1))) {
                sb.append(' ');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * @return 0 for no match; otherwise higher is better (name prefix 3, name 2, description only 1)
     */
    public static int score(String query, String name, String description) {
        if (query == null) {
            return 1;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return 1;
        }
        String lname = name.toLowerCase(Locale.ROOT);
        String human = humanize(name);
        String ldesc = description == null ? "" : description.toLowerCase(Locale.ROOT);
        boolean allInName = true;
        for (String tok : q.split("\\s+")) {
            boolean inName = lname.contains(tok) || human.contains(tok);
            if (!inName && !ldesc.contains(tok)) {
                return 0;
            }
            allInName &= inName;
        }
        if (lname.startsWith(q.replace(" ", "")) || human.startsWith(q)) {
            return 3;
        }
        return allInName ? 2 : 1;
    }
}
