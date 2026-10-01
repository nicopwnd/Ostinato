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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

public class TaskModelTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void commandsMatchBaritoneSyntax() {
        assertEquals("goto 120 64 -340", TaskStep.of(StepType.GOTO, "coords", "120 64 -340").command());
        assertEquals("mine 16 iron_ore coal_ore", TaskStep.of(StepType.MINE, "blocks", "iron_ore, coal_ore", "count", "16").command());
        assertEquals("mine diamond_ore", TaskStep.of(StepType.MINE, "blocks", "diamond_ore").command());
        assertEquals("follow player Steve Alex", TaskStep.of(StepType.FOLLOW, "names", "Steve, Alex").command());
        assertEquals("follow players", TaskStep.of(StepType.FOLLOW, "mode", "players", "names", "ignored").command());
        assertEquals("farm 30", TaskStep.of(StepType.FARM, "range", "30").command());
        assertEquals("farm", TaskStep.of(StepType.FARM).command());
        assertEquals("explore 100 -200", TaskStep.of(StepType.EXPLORE, "origin", "100 -200").command());
        assertEquals("goto crafting_table", TaskStep.of(StepType.GET_TO_BLOCK, "block", "crafting_table").command());
        assertEquals("build house 1 2 3", TaskStep.of(StepType.BUILD, "schematic", "house", "origin", "1 2 3").command());
        assertNull(TaskStep.of(StepType.WAIT, "seconds", "5").command());
        assertNull(TaskStep.of(StepType.SET, "setting", "allowBreak", "value", "false").command());
        assertEquals("#goto 1 2 3", TaskStep.of(StepType.GOTO, "coords", "1 2 3").preview("#"));
    }

    @Test
    public void validation() {
        assertNull(TaskStep.of(StepType.GOTO, "coords", "~ 64 ~10").validate());
        assertNull(TaskStep.of(StepType.GOTO, "coords", "64").validate());
        assertNotNull(TaskStep.of(StepType.GOTO, "coords", "").validate());
        assertNotNull(TaskStep.of(StepType.GOTO, "coords", "1 2 3 4").validate());
        assertNotNull(TaskStep.of(StepType.GOTO, "coords", "a b").validate());
        assertNotNull(TaskStep.of(StepType.MINE, "blocks", "iron_ore", "count", "-3").validate());
        assertNotNull(TaskStep.of(StepType.MINE, "blocks", "iron ore!").validate());
        assertNull(TaskStep.of(StepType.MINE, "blocks", "minecraft:iron_ore", "count", "16").validate());
        assertNotNull(TaskStep.of(StepType.FOLLOW, "mode", "player").validate());
        assertNull(TaskStep.of(StepType.FOLLOW, "mode", "entities", "seconds", "30").validate());
        assertNotNull(TaskStep.of(StepType.EXPLORE, "origin", "1 2 3").validate());
        assertNotNull(TaskStep.of(StepType.GET_TO_BLOCK, "block", "a b").validate());
        assertNotNull(TaskStep.of(StepType.WAIT, "seconds", "0").validate());
        assertNull(TaskStep.of(StepType.WAIT, "seconds", "2.5").validate());
        assertEquals(2500, TaskStep.of(StepType.WAIT, "seconds", "2.5").waitMs());
        assertNotNull(TaskStep.of(StepType.SET, "setting", "allowBreak").validate());
        assertNotNull(TaskStep.of(StepType.BUILD, "schematic", "my house").validate());
    }

    @Test
    public void changingTypeKeepsSharedParams() {
        TaskStep s = TaskStep.of(StepType.FARM, "range", "20", "seconds", "60");
        s.setType(StepType.EXPLORE);
        assertEquals("60", s.get("seconds"));
        assertEquals("", s.get("origin"));
        s.setType(StepType.FOLLOW);
        assertEquals("player", s.get("mode"));
        assertEquals(StepType.GOTO, StepType.SET.next(1));
        assertEquals(StepType.SET, StepType.GOTO.next(-1));
        assertEquals(StepType.GET_TO_BLOCK, StepType.parse("Get to block"));
        assertEquals("Mine iron_ore gold_ore", TaskStep.of(StepType.MINE, "blocks", "iron_ore,gold_ore", "count", "3").summary());
    }

    @Test
    public void listEditing() {
        TaskList l = new TaskList("x");
        l.add(TaskStep.of(StepType.WAIT, "seconds", "1"));
        l.add(TaskStep.of(StepType.WAIT, "seconds", "2"));
        l.add(TaskStep.of(StepType.WAIT, "seconds", "3"));
        assertEquals(0, l.move(1, -1));
        assertEquals("2", l.get(0).get("seconds"));
        assertEquals(0, l.move(0, -1)); // no-op at the top
        assertTrue(l.duplicate(0));
        assertEquals(4, l.size());
        l.get(1).set("seconds", "9");
        assertEquals("2", l.get(0).get("seconds")); // duplicate is a copy
        l.remove(1);
        assertEquals(Arrays.asList("2", "1", "3"), Arrays.asList(l.get(0).get("seconds"), l.get(1).get("seconds"), l.get(2).get("seconds")));
    }

    @Test
    public void jsonAndFilesRoundTrip() throws Exception {
        TaskList l = new TaskList("iron run!");
        l.setLoop(true);
        l.add(TaskStep.of(StepType.GOTO, "coords", "1 2 3"));
        l.add(TaskStep.of(StepType.MINE, "blocks", "iron_ore", "count", "16"));
        l.add(TaskStep.of(StepType.SET, "setting", "allowBreak", "value", "false"));
        TaskFiles files = new TaskFiles(tmp.getRoot().toPath().resolve("tasks"));
        files.save(l);
        assertEquals("iron-run", l.name());
        assertEquals(Arrays.asList("iron-run"), files.list());
        assertTrue(Files.exists(tmp.getRoot().toPath().resolve("tasks/iron-run.json")));
        TaskList back = files.load("IRON-RUN");
        assertEquals("iron-run", back.name());
        assertTrue(back.loop());
        assertEquals(3, back.size());
        assertEquals("mine 16 iron_ore", back.get(1).command());
        assertEquals("false", back.get(2).get("value"));
        try {
            TaskJson.read("{\"steps\":[{\"type\":\"TELEPORT\"}]}", "x");
            fail("unknown type accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertEquals("tasks", TaskFiles.sanitize("  "));
        assertEquals("a-b", TaskFiles.sanitize("../a b"));
    }
}
