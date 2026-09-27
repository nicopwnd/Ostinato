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


package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.gui.tasks.TaskList;
import baritone.gui.tasks.TaskRunner;
import baritone.gui.tasks.game.TaskService;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Chat access to the Ostinato task builder: run, pause, resume, stop and list saved task lists. */
public class TasksCommand extends Command {

    public TasksCommand(IBaritone baritone) {
        super(baritone, "tasks", "task");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        TaskService svc = TaskService.INSTANCE;
        TaskRunner r = svc.runner;
        long now = System.currentTimeMillis();
        String sub = args.hasAny() ? args.getString().toLowerCase() : "status";
        switch (sub) {
            case "run":
            case "start": {
                args.requireMax(1);
                TaskList list;
                if (args.hasAny()) {
                    String name = args.getString();
                    if (svc.dirty) {
                        throw new CommandInvalidStateException("The Tasks tab has unsaved changes to " + svc.list().name() + "; save them first, or use #tasks run to run it as is");
                    }
                    try {
                        list = svc.load(name);
                    } catch (java.nio.file.NoSuchFileException e) {
                        throw new CommandInvalidStateException("No task list named " + name + " in " + svc.files().dir());
                    } catch (Exception e) {
                        throw new CommandInvalidStateException("Could not load " + name + ": " + e.getMessage());
                    }
                } else {
                    list = svc.list();
                }
                r.run(list, now);
                if (r.state() == TaskRunner.State.FAILED) {
                    throw new CommandInvalidStateException("Task list " + list.name() + " cannot run: " + r.error());
                }
                logDirect("Running task list " + list.name() + " (" + list.size() + " steps" + (list.loop() ? ", looping" : "") + ")");
                return;
            }
            case "stop":
                args.requireMax(0);
                if (!r.active()) {
                    throw new CommandInvalidStateException("No task list is running");
                }
                r.stop(now);
                logDirect("Task list stopped");
                return;
            case "pause":
                args.requireMax(0);
                if (r.state() != TaskRunner.State.RUNNING) {
                    throw new CommandInvalidStateException("No task list is running");
                }
                r.pause(now);
                logDirect("Task list paused");
                return;
            case "resume":
                args.requireMax(0);
                if (r.state() != TaskRunner.State.PAUSED) {
                    throw new CommandInvalidStateException("The task list is not paused");
                }
                r.resume(now);
                logDirect("Task list resumed");
                return;
            case "list": {
                args.requireMax(0);
                List<String> names = svc.files().list();
                logDirect(names.isEmpty() ? "No saved task lists in " + svc.files().dir() : "Task lists: " + String.join(", ", names));
                return;
            }
            case "status": {
                args.requireMax(0);
                String line = r.hudLine(now);
                if (line != null) {
                    logDirect(line);
                } else if (r.state() == TaskRunner.State.FAILED) {
                    logDirect("Last run failed at " + r.error());
                } else {
                    logDirect("No task list is running (" + r.state().name().toLowerCase() + ")");
                }
                return;
            }
            default:
                throw new CommandInvalidStateException("Unknown subcommand " + sub + "; see #help tasks");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper().append("run", "stop", "pause", "resume", "list", "status").filterPrefix(args.getString()).stream();
        }
        if (args.hasExactly(2)) {
            String sub = args.getString().toLowerCase();
            if (sub.equals("run") || sub.equals("start")) {
                return new TabCompleteHelper().append(TaskService.INSTANCE.files().list().stream()).filterPrefix(args.getString()).stream();
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Run task lists made in the Ostinato screen";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Runs task lists built in the Tasks tab of the Ostinato screen (saved in baritone/tasks).",
                "",
                "Usage:",
                "> tasks run <name> - Load baritone/tasks/<name>.json and run it",
                "> tasks run - Run the list currently open in the Tasks tab",
                "> tasks pause / resume / stop - Control the running list",
                "> tasks list - List saved task lists",
                "> tasks status - Show the running step"
        );
    }
}
