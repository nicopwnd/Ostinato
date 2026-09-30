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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** One step of a task list: a type plus its parameter values (as typed by the user). */
public final class TaskStep {

    private static final Pattern COORD = Pattern.compile("~|~?-?\\d+(\\.\\d+)?");
    private static final Pattern INT = Pattern.compile("\\d+");
    private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?|\\.\\d+");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_:./\\-]+(\\[[^\\]]*\\])?");

    private StepType type;
    private final Map<String, String> params = new LinkedHashMap<>();

    public TaskStep(StepType type) {
        setType(type);
    }

    public static TaskStep of(StepType type, String... keyValues) {
        TaskStep s = new TaskStep(type);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            s.set(keyValues[i], keyValues[i + 1]);
        }
        return s;
    }

    public StepType type() {
        return type;
    }

    /** Change the type, keeping values of parameters that the new type also has. */
    public void setType(StepType type) {
        Map<String, String> old = new LinkedHashMap<>(params);
        this.type = type;
        params.clear();
        for (ParamSpec p : type.params) {
            String v = old.get(p.key);
            if (p.kind == ParamSpec.Kind.CHOICE && (v == null || !contains(p.options, v))) {
                v = p.initial();
            }
            params.put(p.key, v == null ? "" : v);
        }
    }

    public String get(String key) {
        String v = params.get(key);
        return v == null ? "" : v;
    }

    public void set(String key, String value) {
        if (params.containsKey(key)) {
            params.put(key, value == null ? "" : value.trim());
        }
    }

    public Map<String, String> params() {
        return java.util.Collections.unmodifiableMap(params);
    }

    public TaskStep copy() {
        TaskStep s = new TaskStep(type);
        s.params.putAll(params);
        return s;
    }

    /** Optional run time in milliseconds for follow/farm/explore, or 0 for "until it stops on its own". */
    public long durationMs() {
        if (!type.hasDuration()) {
            return 0;
        }
        return seconds(get("seconds"));
    }

    public long waitMs() {
        return type == StepType.WAIT ? seconds(get("seconds")) : 0;
    }

    private static long seconds(String s) {
        if (s.isEmpty() || !NUMBER.matcher(s).matches()) {
            return 0;
        }
        return Math.round(Double.parseDouble(s) * 1000);
    }

    /** Mine count, or 0 for "mine until told to stop". */
    public int mineCount() {
        String c = get("count");
        return type == StepType.MINE && INT.matcher(c).matches() ? Integer.parseInt(c) : 0;
    }

    /** Block names of a mine step, split on commas and spaces. */
    public String[] blocks() {
        String b = get(type == StepType.GET_TO_BLOCK ? "block" : "blocks").replace(',', ' ').trim();
        return b.isEmpty() ? new String[0] : b.split("\\s+");
    }

    /** @return {@code null} if the step can run, else a short reason. */
    public String validate() {
        for (ParamSpec p : type.params) {
            String v = get(p.key);
            if (v.isEmpty()) {
                if (p.required) {
                    return p.hint + " is required";
                }
                continue;
            }
            switch (p.kind) {
                case INT:
                    if (!INT.matcher(v).matches()) {
                        return p.hint + " must be a whole number";
                    }
                    break;
                case NUMBER:
                    if (!NUMBER.matcher(v).matches()) {
                        return p.hint + " must be a number";
                    }
                    break;
                case COORDS: {
                    String[] t = v.split("\\s+");
                    int want = p.hint.split(" ").length;
                    boolean countOk = type == StepType.GOTO ? t.length >= 1 && t.length <= 3 : t.length == want;
                    if (!countOk) {
                        return type == StepType.GOTO ? "use x y z, x z or y" : "use " + p.hint;
                    }
                    for (String c : t) {
                        if (!COORD.matcher(c).matches()) {
                            return "'" + c + "' is not a coordinate";
                        }
                    }
                    break;
                }
                case CHOICE:
                    if (!contains(p.options, v)) {
                        return p.hint + " must be one of " + String.join("/", p.options);
                    }
                    break;
                default:
                    break;
            }
        }
        switch (type) {
            case MINE:
                for (String b : blocks()) {
                    if (!ID.matcher(b).matches()) {
                        return "'" + b + "' is not a block id";
                    }
                }
                break;
            case GET_TO_BLOCK:
                if (blocks().length != 1 || !ID.matcher(blocks()[0]).matches()) {
                    return "give exactly one block id";
                }
                break;
            case BUILD:
                if (get("schematic").contains(" ")) {
                    return "schematic name cannot contain spaces";
                }
                break;
            case SET:
                if (get("setting").contains(" ")) {
                    return "setting name cannot contain spaces";
                }
                break;
            case FOLLOW: {
                String m = get("mode");
                if ((m.equals("player") || m.equals("entity")) && get("names").isEmpty()) {
                    return "names are required for follow " + m;
                }
                break;
            }
            case WAIT:
                if (waitMs() <= 0) {
                    return "seconds must be above 0";
                }
                break;
            default:
                break;
        }
        return null;
    }

    /**
     * The Baritone chat command this step runs (without the prefix), or {@code null} for steps the runner or
     * adapter handles itself (wait; set is applied without saving settings).
     */
    public String command() {
        switch (type) {
            case GOTO:
                return "goto " + get("coords");
            case MINE: {
                int c = mineCount();
                return "mine " + (c > 0 ? c + " " : "") + String.join(" ", blocks());
            }
            case FOLLOW: {
                String names = get("names").replace(',', ' ').trim();
                String m = get("mode");
                return "follow " + m + ((m.equals("player") || m.equals("entity")) && !names.isEmpty() ? " " + names.replaceAll("\\s+", " ") : "");
            }
            case FARM:
                return get("range").isEmpty() ? "farm" : "farm " + get("range");
            case EXPLORE:
                return get("origin").isEmpty() ? "explore" : "explore " + get("origin");
            case GET_TO_BLOCK:
                return "goto " + get("block");
            case BUILD:
                return get("origin").isEmpty() ? "build " + get("schematic") : "build " + get("schematic") + " " + get("origin");
            default:
                return null;
        }
    }

    /** Human-readable preview: the command, or a description for wait/set. */
    public String preview(String prefix) {
        String c = command();
        if (c != null) {
            return prefix + c;
        }
        if (type == StepType.WAIT) {
            return "wait " + get("seconds") + " s";
        }
        return "set " + get("setting") + " " + get("value") + " (this session only)";
    }

    /** Short label for the HUD: "Mine iron_ore", "Go to 120 64 -340". */
    public String summary() {
        String first = "";
        for (ParamSpec p : type.params) {
            String v = get(p.key);
            if (!v.isEmpty()) {
                first = v;
                break;
            }
        }
        if (type == StepType.MINE) {
            first = String.join(" ", blocks());
        }
        return first.isEmpty() ? type.label : type.label + " " + first;
    }

    private static boolean contains(String[] opts, String v) {
        for (String o : opts) {
            if (o.equals(v.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
