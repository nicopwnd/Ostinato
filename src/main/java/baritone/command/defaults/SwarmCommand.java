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
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.behavior.SwarmBehavior;
import baritone.swarm.SwarmControl;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** {@code #swarm ping [group]}, {@code #swarm status}, {@code #swarm reload}. */
public class SwarmCommand extends Command {

    public SwarmCommand(IBaritone baritone) {
        super(baritone, "swarm");
    }

    private SwarmBehavior swarm() throws CommandInvalidStateException {
        if (!(baritone instanceof Baritone)) {
            throw new CommandInvalidStateException("swarm is not available");
        }
        return ((Baritone) baritone).getSwarmBehavior();
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        SwarmBehavior swarm = swarm();
        switch (action) {
            case "status": {
                args.requireMax(0);
                swarm.statusLines().forEach(this::logDirect);
                return;
            }
            case "ping": {
                args.requireMax(1);
                String group = args.hasAny() ? args.getString() : null;
                SwarmControl control = swarm.control();
                if (control == null) {
                    throw new CommandInvalidStateException("swarm link is not running (" + swarm.state() + ")");
                }
                int n;
                try {
                    n = control.ping(group);
                } catch (IllegalArgumentException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
                logDirect("swarm: pinged " + n + " group(s); replies arrive as rate limits allow, see #swarm status");
                return;
            }
            case "reload": {
                args.requireMax(0);
                swarm.reload();
                logDirect("swarm: reloading roster and keyring");
                return;
            }
            default:
                throw new CommandInvalidTypeException(args.consumed(), "ping, status or reload");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper().append("ping", "status", "reload").filterPrefix(args.getString()).stream();
        }
        if (args.hasExactly(2) && args.getString().equalsIgnoreCase("ping")) {
            SwarmControl control = baritone instanceof Baritone ? ((Baritone) baritone).getSwarmBehavior().control() : null;
            List<String> groups = control == null ? Collections.emptyList() : control.myGroups();
            return new TabCompleteHelper().append(groups.stream()).filterPrefix(args.getString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Swarm link: ping members and show status";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Controls the encrypted swarm link (needs swarmEnabled, a roster file and a sigil keyring).",
                "Every line sent is a sealed sigil token; there is no plaintext mode.",
                "",
                "Usage:",
                "> swarm status - roster, last-seen times and link health",
                "> swarm ping - ping every member of each of your groups",
                "> swarm ping <group> - ping the members of one group",
                "> swarm reload - reload the roster and keyring"
        );
    }
}
