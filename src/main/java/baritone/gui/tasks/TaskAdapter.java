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

/**
 * What the runner needs from Baritone. The in-game implementation drives Baritone's own commands and
 * processes; tests use a fake.
 */
public interface TaskAdapter {

    /** Start the step (never called for WAIT). @return {@code null} on success, else an error message. */
    String start(TaskStep step);

    /** @return true while Baritone is still working on the step last started */
    boolean busy(TaskStep step);

    /** Called once the step went idle. @return {@code null} if it succeeded, else why it did not. */
    String verify(TaskStep step);

    /** Live progress for the HUD/GUI (e.g. "12/16"), or {@code null}. */
    String progress(TaskStep step);

    void pause();

    void resume();

    /** Stop whatever Baritone is doing. */
    void cancel();
}
