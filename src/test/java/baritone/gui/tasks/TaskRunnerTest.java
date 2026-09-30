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

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class TaskRunnerTest {

    /** Scripted Baritone: each started step stays busy for a number of polls, then verifies with a given result. */
    static final class FakeAdapter implements TaskAdapter {
        final List<String> log = new ArrayList<>();
        int busyPolls = 2;
        String startError, verifyError;
        int remaining;

        @Override
        public String start(TaskStep step) {
            log.add("start " + (step.command() != null ? step.command() : step.type()));
            remaining = busyPolls;
            return startError;
        }

        @Override
        public boolean busy(TaskStep step) {
            return remaining-- > 0;
        }

        @Override
        public String verify(TaskStep step) {
            return verifyError;
        }

        @Override
        public String progress(TaskStep step) {
            return step.type() == StepType.MINE ? "3/" + step.mineCount() : null;
        }

        @Override
        public void pause() {
            log.add("pause");
        }

        @Override
        public void resume() {
            log.add("resume");
        }

        @Override
        public void cancel() {
            log.add("cancel");
        }
    }

    FakeAdapter a;
    TaskRunner r;
    long t;

    @Before
    public void setUp() {
        a = new FakeAdapter();
        r = new TaskRunner(a);
        t = 1000;
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            t += 50;
            r.tick(t);
        }
    }

    private static TaskList list(boolean loop, TaskStep... steps) {
        TaskList l = new TaskList("t");
        l.setLoop(loop);
        for (TaskStep s : steps) {
            l.add(s);
        }
        return l;
    }

    @Test
    public void runsStepsInOrder() {
        r.run(list(false, TaskStep.of(StepType.GOTO, "coords", "1 2 3"), TaskStep.of(StepType.MINE, "blocks", "iron_ore", "count", "16")), t);
        assertEquals(TaskRunner.State.RUNNING, r.state());
        ticks(1);
        assertEquals(TaskRunner.StepStatus.RUNNING, r.status(0));
        assertEquals("Task 1/2: Go to 1 2 3", r.hudLine(t));
        ticks(3); // busy, busy, idle -> verify -> done
        assertEquals(TaskRunner.StepStatus.DONE, r.status(0));
        assertEquals(1, r.index());
        ticks(1);
        assertEquals("Task 2/2: Mine iron_ore 3/16", r.hudLine(t));
        ticks(5);
        assertEquals(TaskRunner.State.FINISHED, r.state());
        assertEquals("start goto 1 2 3", a.log.get(0));
        assertEquals("start mine 16 iron_ore", a.log.get(1));
        assertNull(r.hudLine(t));
    }

    @Test
    public void idleWithoutEverBeingBusyWaitsForGrace() {
        a.busyPolls = 0;
        r.run(list(false, TaskStep.of(StepType.FARM)), t);
        ticks(2);
        assertEquals(TaskRunner.StepStatus.RUNNING, r.status(0));
        t += TaskRunner.GRACE_MS;
        r.tick(t);
        assertEquals(TaskRunner.State.FINISHED, r.state());
    }

    @Test
    public void startErrorFailsTheStep() {
        a.startError = "Invalid block";
        r.run(list(false, TaskStep.of(StepType.WAIT, "seconds", "1"), TaskStep.of(StepType.MINE, "blocks", "nope")), t);
        r.tick(t);
        t += 1000;
        ticks(3);
        assertEquals(TaskRunner.State.FAILED, r.state());
        assertEquals(TaskRunner.StepStatus.DONE, r.status(0));
        assertEquals(TaskRunner.StepStatus.FAILED, r.status(1));
        assertEquals("Invalid block", r.message(1));
        assertEquals("step 2: Invalid block", r.error());
    }

    @Test
    public void verifyErrorFailsAndCancels() {
        a.verifyError = "stopped before reaching the goal";
        r.run(list(false, TaskStep.of(StepType.GOTO, "coords", "1 2 3"), TaskStep.of(StepType.GOTO, "coords", "4 5 6")), t);
        ticks(5);
        assertEquals(TaskRunner.State.FAILED, r.state());
        assertEquals(0, r.index());
        assertEquals("stopped before reaching the goal", r.message(0));
        assertEquals(1, a.log.stream().filter(s -> s.startsWith("start")).count());
    }

    @Test
    public void invalidStepFailsBeforeAnythingRuns() {
        r.run(list(false, TaskStep.of(StepType.GOTO, "coords", "1 2 3"), TaskStep.of(StepType.WAIT, "seconds", "")), t);
        assertEquals(TaskRunner.State.FAILED, r.state());
        assertEquals(1, r.index());
        assertTrue(a.log.isEmpty());
        r.run(new TaskList("empty"), t);
        assertEquals(TaskRunner.State.FAILED, r.state());
    }

    @Test
    public void waitUsesTheClockAndPausesFreezeIt() {
        r.run(list(false, TaskStep.of(StepType.WAIT, "seconds", "2")), t);
        r.tick(t);
        t += 1000;
        r.tick(t);
        assertEquals("1s left", r.progress(t));
        r.pause(t);
        t += 60_000;
        r.tick(t);
        assertEquals(TaskRunner.State.PAUSED, r.state());
        r.resume(t);
        r.tick(t);
        assertEquals(TaskRunner.State.RUNNING, r.state());
        t += 1000;
        r.tick(t);
        assertEquals(TaskRunner.State.FINISHED, r.state());
        assertFalse(a.log.contains("pause")); // wait steps don't touch Baritone
        assertEquals(2000, r.elapsed(t));
    }

    @Test
    public void pauseResumeStopDriveBaritone() {
        r.run(list(false, TaskStep.of(StepType.MINE, "blocks", "iron_ore")), t);
        a.busyPolls = 1000;
        ticks(2);
        r.pause(t);
        assertTrue(r.hudLine(t).endsWith("(paused)"));
        r.resume(t);
        r.stop(t);
        assertEquals(TaskRunner.State.STOPPED, r.state());
        assertTrue(a.log.containsAll(java.util.Arrays.asList("pause", "resume", "cancel")));
        ticks(3);
        assertEquals(TaskRunner.State.STOPPED, r.state());
    }

    @Test
    public void timedStepsFinishOnTheClock() {
        a.busyPolls = 100000;
        r.run(list(false, TaskStep.of(StepType.FOLLOW, "mode", "players", "seconds", "10")), t);
        ticks(2);
        assertEquals("0/10s", r.progress(t));
        t += 10_000;
        r.tick(t);
        assertEquals(TaskRunner.State.FINISHED, r.state());
        assertTrue(a.log.contains("cancel"));
    }

    @Test
    public void loopRestartsAndCountsCycles() {
        r.run(list(true, TaskStep.of(StepType.SET, "setting", "a", "value", "b"), TaskStep.of(StepType.SET, "setting", "c", "value", "d")), t);
        ticks(2);
        assertEquals(TaskRunner.State.RUNNING, r.state());
        assertEquals(2, r.cycle());
        assertEquals(0, r.index());
        ticks(4); // set steps finish on the tick they start
        assertEquals(4, r.cycle());
    }

    @Test
    public void runnerWorksOnACopy() {
        TaskList l = list(false, TaskStep.of(StepType.WAIT, "seconds", "1"));
        r.run(l, t);
        l.add(TaskStep.of(StepType.WAIT, "seconds", "1"));
        assertEquals(1, r.list().size());
    }
}
