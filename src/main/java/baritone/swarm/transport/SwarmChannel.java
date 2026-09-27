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

import java.util.Locale;

/** Which chat channel carries swarm lines ({@code swarmChannel}). */
public enum SwarmChannel {
    /** One private message per recipient; broadcasts fan out to every other group member. */
    WHISPER("/msg {to} {msg}"),
    /** Public chat: one line reaches everyone (non-members cannot open it). */
    GLOBAL("{msg}"),
    /** Scoreboard team chat. */
    TEAM("/teammsg {msg}");

    private final String defaultTemplate;

    SwarmChannel(String defaultTemplate) {
        this.defaultTemplate = defaultTemplate;
    }

    public String defaultTemplate() {
        return defaultTemplate;
    }

    /** {@code whisper|global|team} (case-insensitive; {@code msg}, {@code chat}, {@code teammsg} also work). */
    public static SwarmChannel parse(String s) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "global":
            case "chat":
                return GLOBAL;
            case "team":
            case "teammsg":
                return TEAM;
            default:
                return WHISPER;
        }
    }

    /** The template to use: {@code custom} if it is valid for this channel, else the default. */
    public String template(String custom) {
        if (custom == null || custom.trim().isEmpty() || !custom.contains("{msg}")) {
            return defaultTemplate;
        }
        if (this == WHISPER && !custom.contains("{to}")) {
            return defaultTemplate;
        }
        return custom;
    }
}
