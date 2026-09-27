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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds swarm-capable SIGIL tokens ({@code S1C/S2C/S2S.<slug>.<b64>}) in a
 * received chat line. Wrapper nicks are ignored.
 */
public final class ChatTokens {

    public static final int MAX_PER_LINE = 4;

    private static final Pattern FORMATTING = Pattern.compile("\u00a7.?");
    private static final Pattern TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_.\\-])S(?:1C|2C|2S)\\.[a-z0-9]{4}\\.[A-Za-z0-9_-]{38,}"
                    + "(?=$|[\\s,;:!?)\\]}\"'>]|\\.(?:\\s|$))");

    private ChatTokens() {}

    public static List<String> extract(String chatLine) {
        List<String> out = new ArrayList<>();
        if (chatLine == null || chatLine.indexOf('S') < 0) {
            return out;
        }
        Matcher m = TOKEN.matcher(FORMATTING.matcher(chatLine).replaceAll(""));
        while (m.find() && out.size() < MAX_PER_LINE) {
            out.add(m.group());
        }
        return out;
    }
}
