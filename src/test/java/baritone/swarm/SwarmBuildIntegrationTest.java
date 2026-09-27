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


package baritone.swarm;

import baritone.api.schematic.partition.CellSource;
import baritone.api.schematic.partition.PartitionAxis;
import baritone.api.schematic.partition.PartitionPlan;
import baritone.api.schematic.partition.PartitionStrategy;
import baritone.api.schematic.partition.SchematicPartitioner;
import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.crypto.SigilEd25519;
import baritone.swarm.frame.SwarmReject;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.ChatSwarmTransport;
import baritone.swarm.transport.SwarmChannel;
import baritone.swarm.transport.SwarmRateLimiter;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End to end: three bots on a simulated vanilla 1.16.1 chat server, each with its own S2S signet, the default
 * rate limits, the real partitioner and a shared world. The lead starts a build with {@link SwarmBuild} and every
 * bot places only the cells of its own region, one per tick, until the world matches the schematic.
 */
public class SwarmBuildIntegrationTest {

    private static final String[] BOTS = {"Alice", "Bob", "Carol"};
    private static final long TICK_MS = 50;
    private static final int W = 12, H = 6, L = 9;

    private final AtomicLong clock = new AtomicLong(1_790_000_000_000L);
    private final List<String> log = new ArrayList<>();
    private SwarmRoster roster;
    private Map<String, SigilCircle> circles;
    private Map<String, SigilEd25519> signets;

    /** The target: a walled, floored house with a doorway and an empty inside, air elsewhere. */
    static boolean target(int x, int y, int z) {
        if (y == 0) {
            return true;
        }
        boolean wall = x == 0 || x == W - 1 || z == 0 || z == L - 1;
        boolean door = z == 0 && x == W / 2 && y <= 2;
        return (wall && !door) || y == H - 1;
    }

    /** The shared world: who placed each cell (null = air). */
    static final class World {
        final String[][][] placedBy = new String[W][H][L];
        final List<String> violations = new ArrayList<>();
        int placements;

        void place(String bot, int x, int y, int z) {
            placements++;
            if (placedBy[x][y][z] != null) {
                violations.add(bot + " placed " + x + "," + y + "," + z + " already placed by " + placedBy[x][y][z]);
            }
            if (y > 0 && target(x, y - 1, z) && placedBy[x][y - 1][z] == null) {
                violations.add(bot + " placed floating " + x + "," + y + "," + z);
            }
            placedBy[x][y][z] = bot;
        }

        boolean matches() {
            for (int x = 0; x < W; x++) {
                for (int y = 0; y < H; y++) {
                    for (int z = 0; z < L; z++) {
                        if ((placedBy[x][y][z] != null) != target(x, y, z)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }
    }

    /**
     * Stands in for BuilderProcess.buildRegion: partitions exactly as the game side does (from the order's
     * parameters, never local settings) and places one block of its region per tick, bottom-up. It only waits
     * on a cell whose support is not there yet, like Baritone waiting for a neighbour.
     */
    static final class SimBuilder implements SwarmBuild.RegionBuilder {
        final String bot;
        final World world;
        final List<int[]> todo = new ArrayList<>();
        PartitionPlan plan;
        int started;

        SimBuilder(String bot, World world) {
            this.bot = bot;
            this.world = world;
        }

        @Override
        public void start(SwarmBuild.Order o) {
            CellSource cells = SwarmBuildIntegrationTest::target;
            plan = SchematicPartitioner.partition(W, H, L, cells, o.count, PartitionStrategy.parse(o.strategy),
                    o.seam, PartitionAxis.parse(o.axis), o.columns);
            todo.clear();
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    for (int z = 0; z < L; z++) {
                        if (plan.owner(x, y, z) == o.index && target(x, y, z) && world.placedBy[x][y][z] == null) {
                            todo.add(new int[]{x, y, z});
                        }
                    }
                }
            }
            started++;
        }

        /** One game tick of building. */
        void work() {
            for (int i = 0; i < todo.size(); i++) {
                int[] c = todo.get(i);
                if (c[1] == 0 || !target(c[0], c[1] - 1, c[2]) || world.placedBy[c[0]][c[1] - 1][c[2]] != null) {
                    world.place(bot, c[0], c[1], c[2]);
                    todo.remove(i);
                    return;
                }
            }
        }

        @Override
        public boolean busy() {
            return !todo.isEmpty();
        }

        @Override
        public void cancel() {
            todo.clear();
        }
    }

    /** Vanilla 1.16.1 chat: whispers and global lines, routed by the command text only. Records every line. */
    static final class ChatServer {
        final Map<String, ChatSwarmTransport> online = new LinkedHashMap<>();
        final List<String> lines = new ArrayList<>();
        Predicate<String> drop = c -> false;

        void deliver(String sender, String command) {
            lines.add(command);
            if (drop.test(command)) {
                return;
            }
            if (command.startsWith("/msg ")) {
                String rest = command.substring(5);
                int sp = rest.indexOf(' ');
                String to = rest.substring(0, sp);
                String msg = rest.substring(sp + 1);
                ChatSwarmTransport t = online.get(to);
                if (t != null) {
                    t.onChat(sender + " whispers to you: " + msg);
                }
                online.get(sender).onChat("You whisper to " + to + ": " + msg);
            } else {
                for (ChatSwarmTransport t : online.values()) {
                    t.onChat("<" + sender + "> " + command);
                }
            }
        }

        void flushAll(long nowMs) {
            for (Map.Entry<String, ChatSwarmTransport> e : online.entrySet()) {
                List<String> out = new ArrayList<>();
                e.getValue().flush(nowMs, out::add);
                out.forEach(c -> deliver(e.getKey(), c));
            }
        }
    }

    final class Bot {
        final String name;
        final SwarmControl control;
        final SimBuilder builder;

        Bot(String name, ChatServer server, World world) throws Exception {
            this.name = name;
            ChatSwarmTransport t = new ChatSwarmTransport(name, SwarmChannel.WHISPER, "", roster,
                    new SwarmRateLimiter(0.5, 5, false, 64, 0), 256); // the setting defaults
            server.online.put(name, t);
            SwarmConfig.Builder cfg = SwarmConfig.builder(); // requireSignedSender defaults on
            cfg.lineReserveChars = Math.max(cfg.lineReserveChars,
                    ChatSwarmTransport.templateOverhead(t.templateFor(t.channelFor("crew"))));
            SwarmEndpoint e = new SwarmEndpoint(name, cfg.build(), circles, t, clock::get);
            e.setMemberCheck(roster::isMember);
            e.setSigning(signets.get(name), signets);
            control = new SwarmControl(roster, e, new SwarmControl.LocalStatus() {
                @Override
                public String name() { return name; }
                @Override
                public String position() { return "0,64,0"; }
                @Override
                public String process() { return "idle"; }
            }, clock::get, log::add, null);
            builder = new SimBuilder(name, world);
            control.enableBuild(builder);
        }
    }

    @Before
    public void setUp() throws Exception {
        roster = SwarmRoster.parse("group crew circle=swarm-test-alpha members=Alice,Bob,Carol lead=Alice\n");
        circles = new LinkedHashMap<>();
        circles.put("crew", TestCircles.alpha());
        signets = new LinkedHashMap<>();
        for (String b : BOTS) {
            signets.put(b, SigilEd25519.testSignet(b + "-DO-NOT-USE"));
        }
    }

    private List<Bot> bots(ChatServer server, World world) throws Exception {
        List<Bot> out = new ArrayList<>();
        for (String b : BOTS) {
            out.add(new Bot(b, server, world));
        }
        return out;
    }

    /** Run game ticks until the lead's job is over or {@code maxMs} of game time passed. @return ms used */
    private long run(ChatServer server, List<Bot> bots, long maxMs) {
        long t = 0;
        do {
            t += TICK_MS;
            clock.addAndGet(TICK_MS);
            server.flushAll(clock.get());
            for (Bot b : bots) {
                b.builder.work();
                b.control.tick();
            }
        } while (bots.get(0).control.build().jobRunning() && t < maxMs);
        return t;
    }

    /** Run game ticks for {@code ms}, job or not. */
    private void runFor(ChatServer server, List<Bot> bots, long ms) {
        for (long t = 0; t < ms; t += TICK_MS) {
            clock.addAndGet(TICK_MS);
            server.flushAll(clock.get());
            for (Bot b : bots) {
                b.builder.work();
                b.control.tick();
            }
        }
    }

    private void assertAllSealedAndQuiet(ChatServer server, List<Bot> bots) {
        assertFalse(server.lines.isEmpty());
        for (String line : server.lines) {
            assertTrue(line, line.startsWith("/msg "));
            assertTrue(line, line.contains(" S2S.")); // every line is a signed, sealed token
            assertFalse(line, line.contains("house") || line.contains("BUILD"));
        }
        for (Bot b : bots) {
            for (SwarmReject r : new SwarmReject[]{SwarmReject.UNSEALED, SwarmReject.WRONG_SIGNER,
                    SwarmReject.MALFORMED, SwarmReject.NOT_MEMBER, SwarmReject.REPLAY}) {
                assertEquals(b.name + " " + r, 0, b.control.endpoint().rejectCount(r));
            }
        }
    }

    private void buildAndCheck(String strategy, String axis, int seam, int columns) throws Exception {
        ChatServer server = new ChatServer();
        World world = new World();
        List<Bot> bots = bots(server, world);
        bots.get(0).control.build().start("crew", "house.schem", 100, 64, -40, strategy, axis, seam, columns);
        long used = run(server, bots, 30 * 60_000L);
        String all = String.join("\n", log);
        assertFalse(all, bots.get(0).control.build().jobRunning());
        assertTrue(all, all.contains("finished, 3 region(s)"));
        assertTrue(world.violations.toString(), world.violations.isEmpty());
        assertTrue("world does not match the schematic", world.matches());
        // every bot built exactly its own region, and all of it
        PartitionPlan plan = bots.get(0).builder.plan;
        for (int i = 0; i < BOTS.length; i++) {
            assertEquals(BOTS[i], 1, bots.get(i).builder.started);
            assertEquals(plan, bots.get(i).builder.plan); // same plan computed on every bot
        }
        for (int x = 0; x < W; x++) {
            for (int y = 0; y < H; y++) {
                for (int z = 0; z < L; z++) {
                    if (target(x, y, z)) {
                        assertEquals(x + "," + y + "," + z, BOTS[plan.owner(x, y, z)], world.placedBy[x][y][z]);
                    }
                }
            }
        }
        assertAllSealedAndQuiet(server, bots);
        assertTrue("took " + used + " ms", used < 10 * 60_000L);
    }

    @Test
    public void stripsBuildTheHouseTogether() throws Exception {
        buildAndCheck("strips", "auto", 1, 0);
    }

    @Test
    public void gridBuildTheHouseTogether() throws Exception {
        buildAndCheck("grid", "x", 1, 0);
    }

    @Test
    public void layersBuildBandByBand() throws Exception {
        ChatServer server = new ChatServer();
        World world = new World();
        List<Bot> bots = bots(server, world);
        bots.get(0).control.build().start("crew", "house.schem", 0, 64, 0, "layers", "auto", 0, 0);
        // until Alice's bottom band is done nobody else may have started
        while (bots.get(0).builder.busy()) {
            run(server, bots, TICK_MS);
            assertEquals(0, bots.get(1).builder.started);
            assertEquals(0, bots.get(2).builder.started);
        }
        run(server, bots, 30 * 60_000L);
        assertTrue(world.violations.toString(), world.violations.isEmpty()); // no floating blocks
        assertTrue(world.matches());
        assertAllSealedAndQuiet(server, bots);
    }

    @Test
    public void lostOrderIsResent() throws Exception {
        ChatServer server = new ChatServer();
        World world = new World();
        List<Bot> bots = bots(server, world);
        boolean[] dropped = {false};
        server.drop = c -> {
            if (!dropped[0] && c.startsWith("/msg Carol ")) {
                dropped[0] = true; // the first line to Carol (her order) never arrives
                return true;
            }
            return false;
        };
        bots.get(0).control.build().start("crew", "house.schem", 0, 64, 0, "strips", "auto", 1, 0);
        run(server, bots, 30 * 60_000L);
        assertTrue(dropped[0]);
        assertTrue(String.join("\n", log), world.matches());
        assertFalse(bots.get(0).control.build().jobRunning());
    }

    @Test
    public void memberCannotOrderAsTheLead() throws Exception {
        ChatServer server = new ChatServer();
        World world = new World();
        List<Bot> bots = bots(server, world);
        // Bob holds the circle key and his own pinned signet, and forges a frame that claims to be from Alice
        ChatSwarmTransport forged = new ChatSwarmTransport("Alice", SwarmChannel.WHISPER, "", roster,
                new SwarmRateLimiter(0.5, 5, false, 64, 0), 256);
        SwarmEndpoint forger = new SwarmEndpoint("Alice", SwarmConfig.defaults(), circles, forged, clock::get);
        forger.setSigning(signets.get("Bob"), signets);
        forger.send("crew", "Carol", SwarmBuild.BUILD,
                new SwarmBuild.Order("x", "h.schem", 0, 64, 0, 0, 1, "strips", "auto", 1, 0).body());
        List<String> out = new ArrayList<>();
        forged.flush(clock.get(), out::add);
        assertFalse(out.isEmpty());
        out.forEach(c -> server.deliver("Bob", c)); // Bob's client sends it
        runFor(server, bots, 5_000);
        assertEquals(0, bots.get(2).builder.started);
        assertNull(bots.get(2).control.build().myOrder());
        assertTrue(bots.get(2).control.endpoint().rejectCount(SwarmReject.WRONG_SIGNER) >= 1); // every frame
        // and a real member who is not the lead is refused by the roster check in SwarmBuild
        bots.get(1).control.endpoint().send("crew", "Carol", SwarmBuild.BUILD,
                new SwarmBuild.Order("y", "house.schem", 0, 64, 0, 0, 1, "strips", "auto", 1, 0).body());
        runFor(server, bots, 5_000);
        assertEquals(0, bots.get(2).builder.started);
        assertTrue(String.join("\n", log), log.stream().anyMatch(l -> l.contains("ignored build order from Bob")));
        assertEquals(0, world.placements);
    }

    @Test
    public void stopHaltsEveryone() throws Exception {
        ChatServer server = new ChatServer();
        World world = new World();
        List<Bot> bots = bots(server, world);
        bots.get(0).control.build().start("crew", "house.schem", 0, 64, 0, "strips", "auto", 1, 0);
        while (bots.get(1).builder.started == 0) {
            runFor(server, bots, TICK_MS);
        }
        assertTrue(bots.get(1).builder.busy());
        bots.get(0).control.build().stop();
        runFor(server, bots, 20_000);
        int after = world.placements;
        runFor(server, bots, 20_000);
        assertEquals(after, world.placements);
        for (Bot b : bots) {
            assertFalse(b.name, b.builder.busy());
        }
        assertFalse(world.matches());
    }
}
