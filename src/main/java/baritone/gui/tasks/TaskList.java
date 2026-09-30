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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An ordered, named list of steps, optionally looping. */
public final class TaskList {

    public static final int MAX_STEPS = 64;

    private String name;
    private boolean loop;
    private final List<TaskStep> steps = new ArrayList<>();

    public TaskList(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean loop() {
        return loop;
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    public List<TaskStep> steps() {
        return Collections.unmodifiableList(steps);
    }

    public int size() {
        return steps.size();
    }

    public TaskStep get(int i) {
        return steps.get(i);
    }

    public boolean add(TaskStep s) {
        if (steps.size() >= MAX_STEPS) {
            return false;
        }
        steps.add(s);
        return true;
    }

    public void remove(int i) {
        if (i >= 0 && i < steps.size()) {
            steps.remove(i);
        }
    }

    /** Move step i by dir (-1 up, +1 down); returns its new index. */
    public int move(int i, int dir) {
        int j = i + dir;
        if (i < 0 || i >= steps.size() || j < 0 || j >= steps.size()) {
            return i;
        }
        Collections.swap(steps, i, j);
        return j;
    }

    /** Insert a copy of step i right after it. */
    public boolean duplicate(int i) {
        if (i < 0 || i >= steps.size() || steps.size() >= MAX_STEPS) {
            return false;
        }
        steps.add(i + 1, steps.get(i).copy());
        return true;
    }

    public TaskList copy() {
        TaskList l = new TaskList(name);
        l.loop = loop;
        for (TaskStep s : steps) {
            l.steps.add(s.copy());
        }
        return l;
    }
}
