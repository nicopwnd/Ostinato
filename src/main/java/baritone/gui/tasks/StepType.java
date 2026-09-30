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


package baritone.gui.tasks;

/** The kinds of step a task list can contain. Each maps onto an existing Baritone command or process. */
public enum StepType {
    GOTO("Go to", ParamSpec.coords("coords", "x y z", 1f, true)),
    MINE("Mine", ParamSpec.text("blocks", "blocks", 1.6f, true), ParamSpec.integer("count", "count", 0.5f, false)),
    FOLLOW("Follow", ParamSpec.choice("mode", "who", 0.7f, "player", "players", "entity", "entities"),
            ParamSpec.text("names", "names", 1.1f, false), ParamSpec.number("seconds", "for s", 0.5f, false)),
    FARM("Farm", ParamSpec.integer("range", "range", 0.6f, false), ParamSpec.number("seconds", "for s", 0.5f, false)),
    EXPLORE("Explore", ParamSpec.coords("origin", "x z", 1f, false), ParamSpec.number("seconds", "for s", 0.5f, false)),
    GET_TO_BLOCK("Get to block", ParamSpec.text("block", "block", 1f, true)),
    BUILD("Build", ParamSpec.text("schematic", "schematic", 1.2f, true), ParamSpec.coords("origin", "x y z", 1f, false)),
    WAIT("Wait", ParamSpec.number("seconds", "seconds", 0.5f, true)),
    SET("Set", ParamSpec.text("setting", "setting", 1f, true), ParamSpec.text("value", "value", 0.8f, true));

    public final String label;
    public final ParamSpec[] params;

    StepType(String label, ParamSpec... params) {
        this.label = label;
        this.params = params;
    }

    /** Steps that never finish on their own (follow/farm/explore) accept an optional run time. */
    public boolean hasDuration() {
        return this == FOLLOW || this == FARM || this == EXPLORE;
    }

    public StepType next(int dir) {
        StepType[] v = values();
        return v[((ordinal() + dir) % v.length + v.length) % v.length];
    }

    public static StepType parse(String s) {
        if (s == null) {
            return null;
        }
        String n = s.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (StepType t : values()) {
            if (t.name().equals(n) || t.label.equalsIgnoreCase(s.trim())) {
                return t;
            }
        }
        return null;
    }
}
