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

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class FreecamCommand extends Command {

    public FreecamCommand(IBaritone baritone) {
        super(baritone, "freecam");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);
        ((Baritone) baritone).getFreecamBehavior().toggle();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Fly a detached camera and pick where the bot goes";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Toggles a camera body that walks and jumps like a player (double-tap jump to fly),",
                "collides with blocks, and cannot leave the bot's render distance, so you can race the bot.",
                "",
                "Left click: follow the entity under the crosshair, else travel to the block under it.",
                "Right click: travel to the camera's position.",
                "",
                "Usage:",
                "> freecam - toggle freecam"
        );
    }
}
