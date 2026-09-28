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

/** String formatting for the path-status HUD. Pure Java. */
public final class HudFormat {

    private HudFormat() {}

    /** Ticks as seconds at 20 TPS: "21.4s", "2m 05s", or "--" when unknown. */
    public static String seconds(double ticks) {
        if (Double.isNaN(ticks) || Double.isInfinite(ticks) || ticks < 0) {
            return "--";
        }
        double s = ticks / 20.0;
        if (s < 100) {
            return String.format(Locale.ROOT, "%.1fs", s);
        }
        long total = Math.round(s);
        return String.format(Locale.ROOT, "%dm %02ds", total / 60, total % 60);
    }

    public static String ticks(double ticks) {
        if (Double.isNaN(ticks) || Double.isInfinite(ticks) || ticks < 0) {
            return "";
        }
        return Math.round(ticks) + " t";
    }

    public static String distance(double blocks) {
        if (Double.isNaN(blocks) || blocks < 0) {
            return "";
        }
        return blocks < 1000 ? String.format(Locale.ROOT, "%.1fm", blocks) : String.format(Locale.ROOT, "%.1fkm", blocks / 1000);
    }

    /**
     * Short goal label. With a position ("GoalBlock", {57,67,70}) gives "Block 57 67 70"; otherwise the goal's
     * toString is compacted: "GoalXZ{x=100,z=-20}" gives "XZ 100 -20".
     */
    public static String goal(String simpleClassName, int[] pos, String toString) {
        String kind = simpleClassName == null ? "" : simpleClassName.replaceFirst("^Goal", "");
        if (pos != null && pos.length == 3) {
            return (kind.isEmpty() ? "" : kind + " ") + pos[0] + " " + pos[1] + " " + pos[2];
        }
        if (toString == null) {
            return kind;
        }
        String s = toString.replaceFirst("^Goal", "")
                .replaceAll("[A-Za-z]+=", "")
                .replaceAll("[{},\\[\\]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s.isEmpty() ? kind : s;
    }
}
