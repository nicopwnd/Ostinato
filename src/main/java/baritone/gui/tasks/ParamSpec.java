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

/** One editable parameter of a task step: a text box, number box or a choice cycled by clicking. */
public final class ParamSpec {

    public enum Kind { TEXT, INT, NUMBER, COORDS, CHOICE }

    public final String key;
    public final String hint;
    public final Kind kind;
    public final float weight;
    public final boolean required;
    public final String[] options;

    private ParamSpec(String key, String hint, Kind kind, float weight, boolean required, String[] options) {
        this.key = key;
        this.hint = hint;
        this.kind = kind;
        this.weight = weight;
        this.required = required;
        this.options = options;
    }

    public static ParamSpec text(String key, String hint, float weight, boolean required) {
        return new ParamSpec(key, hint, Kind.TEXT, weight, required, null);
    }

    public static ParamSpec integer(String key, String hint, float weight, boolean required) {
        return new ParamSpec(key, hint, Kind.INT, weight, required, null);
    }

    public static ParamSpec number(String key, String hint, float weight, boolean required) {
        return new ParamSpec(key, hint, Kind.NUMBER, weight, required, null);
    }

    public static ParamSpec coords(String key, String hint, float weight, boolean required) {
        return new ParamSpec(key, hint, Kind.COORDS, weight, required, null);
    }

    public static ParamSpec choice(String key, String hint, float weight, String... options) {
        return new ParamSpec(key, hint, Kind.CHOICE, weight, true, options);
    }

    /** Default value for a new step: the first option of a choice, otherwise empty. */
    public String initial() {
        return kind == Kind.CHOICE ? options[0] : "";
    }
}
