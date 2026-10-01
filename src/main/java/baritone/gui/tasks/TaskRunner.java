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

import java.util.Arrays;

/**
 * Runs a task list one step at a time. Pure state machine: the caller supplies the time on every call and all
 * Baritone access goes through {@link TaskAdapter}.
 * <p>
 * A started step is finished when the adapter reports it idle (after a short grace period, since processes pick
 * up new work on the next tick) and {@link TaskAdapter#verify} accepts it; wait steps and timed
 * follow/farm/explore steps finish on the clock. Any error stops the run and is kept on that step.
 */
public final class TaskRunner {

    public enum State { IDLE, RUNNING, PAUSED, FINISHED, FAILED, STOPPED }

    public enum StepStatus { PENDING, RUNNING, DONE, FAILED }

    /** How long a freshly started step may look idle before that counts as "finished". */
    public static final long GRACE_MS = 1500;

    private final TaskAdapter adapter;
    private TaskList list;
    private State state = State.IDLE;
    private StepStatus[] status = new StepStatus[0];
    private String[] message = new String[0];
    private int index;
    private int cycle;
    private boolean started;
    private boolean sawBusy;
    private long stepStart, pausedAt, runStart, pausedTotal;
    private String error;

    public TaskRunner(TaskAdapter adapter) {
        this.adapter = adapter;
    }

    public State state() {
        return state;
    }

    public boolean active() {
        return state == State.RUNNING || state == State.PAUSED;
    }

    public TaskList list() {
        return list;
    }

    public int index() {
        return index;
    }

    public int cycle() {
        return cycle;
    }

    public String error() {
        return error;
    }

    public StepStatus status(int i) {
        return i >= 0 && i < status.length ? status[i] : StepStatus.PENDING;
    }

    /** Result note for a step ("reached in 38.2s", an error, ...), or {@code null}. */
    public String message(int i) {
        return i >= 0 && i < message.length ? message[i] : null;
    }

    public long elapsed(long now) {
        if (list == null || runStart == 0) {
            return 0;
        }
        long end = state == State.PAUSED ? pausedAt : now;
        return Math.max(0, end - runStart - pausedTotal);
    }

    /** Start (or restart) a list. Every step is validated first; an invalid one fails the run before anything moves. */
    public void run(TaskList source, long now) {
        if (active()) {
            stop(now);
        }
        list = source.copy();
        status = new StepStatus[list.size()];
        message = new String[list.size()];
        Arrays.fill(status, StepStatus.PENDING);
        index = 0;
        cycle = 1;
        started = false;
        error = null;
        runStart = now;
        pausedTotal = 0;
        if (list.size() == 0) {
            state = State.FAILED;
            error = "the list has no steps";
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            String err = list.get(i).validate();
            if (err != null) {
                index = i;
                fail(err);
                return;
            }
        }
        state = State.RUNNING;
    }

    public void pause(long now) {
        if (state != State.RUNNING) {
            return;
        }
        state = State.PAUSED;
        pausedAt = now;
        if (started && current().type() != StepType.WAIT && current().type() != StepType.SET) {
            adapter.pause();
        }
    }

    public void resume(long now) {
        if (state != State.PAUSED) {
            return;
        }
        long d = now - pausedAt;
        pausedTotal += d;
        stepStart += d;
        state = State.RUNNING;
        if (started && current().type() != StepType.WAIT && current().type() != StepType.SET) {
            adapter.resume();
        }
    }

    public void stop(long now) {
        if (!active()) {
            return;
        }
        if (started) {
            adapter.cancel();
        }
        if (state == State.PAUSED) {
            pausedTotal += now - pausedAt;
        }
        if (index < status.length && status[index] == StepStatus.RUNNING) {
            status[index] = StepStatus.PENDING;
            message[index] = "stopped";
        }
        started = false;
        state = State.STOPPED;
    }

    /** Forget the last run's per-step results (the list was edited). */
    public void clear() {
        if (!active()) {
            list = null;
            state = State.IDLE;
            status = new StepStatus[0];
            message = new String[0];
            error = null;
            runStart = 0;
        }
    }

    private TaskStep current() {
        return list.get(index);
    }

    public void tick(long now) {
        if (state != State.RUNNING) {
            return;
        }
        TaskStep step = current();
        if (!started) {
            status[index] = StepStatus.RUNNING;
            message[index] = null;
            stepStart = now;
            sawBusy = false;
            started = true;
            if (step.type() != StepType.WAIT) {
                String err;
                try {
                    err = adapter.start(step);
                } catch (RuntimeException e) {
                    err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                if (err != null) {
                    fail(err);
                    return;
                }
            }
            if (step.type() == StepType.SET) {
                complete(now);
            }
            return;
        }
        long inStep = now - stepStart;
        if (step.type() == StepType.WAIT) {
            if (inStep >= step.waitMs()) {
                complete(now);
            }
            return;
        }
        long dur = step.durationMs();
        if (dur > 0 && inStep >= dur) {
            adapter.cancel();
            complete(now);
            return;
        }
        boolean busy = adapter.busy(step);
        if (busy) {
            sawBusy = true;
            return;
        }
        if (sawBusy || inStep >= GRACE_MS) {
            String err = adapter.verify(step);
            if (err != null) {
                fail(err);
            } else {
                complete(now);
            }
        }
    }

    private void complete(long now) {
        status[index] = StepStatus.DONE;
        message[index] = "done in " + secs(now - stepStart);
        started = false;
        index++;
        if (index >= list.size()) {
            if (list.loop()) {
                index = 0;
                cycle++;
                Arrays.fill(status, StepStatus.PENDING);
            } else {
                index = list.size() - 1;
                state = State.FINISHED;
            }
        }
    }

    private void fail(String err) {
        if (started) {
            adapter.cancel();
        }
        started = false;
        status[index] = StepStatus.FAILED;
        message[index] = err;
        error = "step " + (index + 1) + ": " + err;
        state = State.FAILED;
    }

    private static String secs(long ms) {
        return String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    /** Progress of the running step: adapter progress, or the clock for wait/timed steps. */
    public String progress(long now) {
        if (!active() || !started) {
            return null;
        }
        TaskStep s = current();
        long inStep = (state == State.PAUSED ? pausedAt : now) - stepStart;
        if (s.type() == StepType.WAIT) {
            return Math.max(0, (s.waitMs() - inStep + 999) / 1000) + "s left";
        }
        String p = adapter.progress(s);
        if (p == null && s.durationMs() > 0) {
            p = inStep / 1000 + "/" + s.durationMs() / 1000 + "s";
        }
        return p;
    }

    /** "Task 2/5: Mine iron_ore 12/16", or {@code null} when nothing is running. */
    public String hudLine(long now) {
        if (!active()) {
            return null;
        }
        String p = progress(now);
        return "Task " + (index + 1) + "/" + list.size() + ": " + current().summary() + (p == null ? "" : " " + p)
                + (state == State.PAUSED ? " (paused)" : "");
    }
}
