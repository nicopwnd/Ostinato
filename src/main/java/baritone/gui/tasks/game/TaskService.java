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


package baritone.gui.tasks.game;

import baritone.gui.tasks.TaskFiles;
import baritone.gui.tasks.TaskList;
import baritone.gui.tasks.TaskRunner;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;

/** Game-side home of the task builder: the list being edited, the runner and the {@code baritone/tasks} folder. */
public final class TaskService {

    public static final TaskService INSTANCE = new TaskService();

    public final TaskRunner runner = new TaskRunner(new BaritoneTaskAdapter());
    private TaskList list = new TaskList("tasks");
    /** The edited list has changes that are not saved to its file. */
    public boolean dirty;
    /** Name the edited list was last loaded from or saved as; saving under any other existing name asks first. */
    public String fileName;
    private TaskFiles files;

    private TaskService() {}

    public TaskFiles files() {
        if (files == null) {
            files = new TaskFiles(Minecraft.getInstance().gameDir.toPath().resolve("baritone").resolve("tasks"));
        }
        return files;
    }

    public TaskList list() {
        return list;
    }

    public void setList(TaskList list) {
        this.list = list;
        runner.clear();
    }

    public TaskList load(String name) throws IOException {
        TaskList l = files().load(name);
        setList(l);
        dirty = false;
        fileName = l.name();
        return l;
    }

    public Path save() throws IOException {
        Path p = files().save(list);
        dirty = false;
        fileName = list.name();
        return p;
    }

    public void tick() {
        runner.tick(System.currentTimeMillis());
    }
}
