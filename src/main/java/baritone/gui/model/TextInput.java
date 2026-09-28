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

/**
 * Minimal single-line text editing state (buffer, caret, select-all) for the settings screen's search box and
 * inline value editors. Rendering and key mapping live on the Minecraft side.
 */
public final class TextInput {

    private final StringBuilder text = new StringBuilder();
    private final int maxLength;
    private int caret;
    private boolean allSelected;

    public TextInput(int maxLength) {
        this.maxLength = maxLength;
    }

    public String get() {
        return text.toString();
    }

    public void set(String s) {
        text.setLength(0);
        text.append(s == null ? "" : s.length() > maxLength ? s.substring(0, maxLength) : s);
        caret = text.length();
        allSelected = false;
    }

    public int caret() {
        return caret;
    }

    public boolean isAllSelected() {
        return allSelected;
    }

    public void selectAll() {
        allSelected = text.length() > 0;
        caret = text.length();
    }

    public boolean isEmpty() {
        return text.length() == 0;
    }

    public void insert(String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        if (allSelected) {
            text.setLength(0);
            caret = 0;
            allSelected = false;
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c != 127 && c != '\u00a7') {
                clean.append(c);
            }
        }
        int room = maxLength - text.length();
        if (room <= 0) {
            return;
        }
        String ins = clean.length() > room ? clean.substring(0, room) : clean.toString();
        text.insert(caret, ins);
        caret += ins.length();
    }

    public void backspace() {
        if (allSelected) {
            set("");
            return;
        }
        if (caret > 0) {
            text.deleteCharAt(caret - 1);
            caret--;
        }
    }

    public void delete() {
        if (allSelected) {
            set("");
            return;
        }
        if (caret < text.length()) {
            text.deleteCharAt(caret);
        }
    }

    public void left() {
        allSelected = false;
        caret = Math.max(0, caret - 1);
    }

    public void right() {
        allSelected = false;
        caret = Math.min(text.length(), caret + 1);
    }

    public void home() {
        allSelected = false;
        caret = 0;
    }

    public void end() {
        allSelected = false;
        caret = text.length();
    }
}
