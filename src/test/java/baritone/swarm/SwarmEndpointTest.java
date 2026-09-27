package baritone.swarm;

import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.crypto.SigilS1C;
import baritone.swarm.crypto.SigilS2C;
import baritone.swarm.crypto.SigilCodec;
import baritone.swarm.crypto.SigilWire;
import baritone.swarm.frame.SwarmFrame;
import baritone.swarm.frame.SwarmFrameException;
import baritone.swarm.frame.SwarmMessage;
import baritone.swarm.frame.SwarmReject;
import baritone.swarm.transport.InMemorySwarmBus;
import baritone.swarm.transport.SwarmTransport;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SwarmEndpointTest {

    private final AtomicLong clock = new AtomicLong(1_790_000_000_000L);
    private InMemorySwarmBus bus;
    private Map<String, SigilCircle> groups;

    @Before
    public void setUp() throws Exception {
        bus = new InMemorySwarmBus();
        groups = new LinkedHashMap<>();
        groups.put("alpha", TestCircles.alpha());
        groups.put("beta", TestCircles.beta());
    }

    private static SwarmConfig unsignedCfg() {
        SwarmConfig.Builder b = SwarmConfig.builder();
        b.requireSignedSender = false;
        return b.build();
    }

    private SwarmEndpoint endpoint(String id) throws Exception {
        return new SwarmEndpoint(id, unsignedCfg(), groups, bus.register(id), clock::get);
    }

    @Test
    public void addressedMessageDeliveredBetweenTwoEndpoints() throws Exception {
        SwarmEndpoint a = endpoint("botA");
        SwarmEndpoint b = endpoint("botB");
        long id = a.send("alpha", "botB", "task", "mine 10 20 30|diamond_ore");
        List<SwarmMessage> got = b.poll();
        assertEquals(1, got.size());
        SwarmMessage m = got.get(0);
        assertEquals("alpha", m.group());
        assertEquals("botA", m.from());
        assertEquals("botB", m.to());
        assertEquals(a.epoch(), m.epoch());
        assertEquals(id, m.msgId());
        assertEquals("task", m.type());
        assertEquals("mine 10 20 30|diamond_ore", m.body());
        assertTrue(a.poll().isEmpty());
        assertTrue(b.rejectCounts().isEmpty());
    }

    @Test
    public void everyLineOnTheWireIsASingleSealedToken() throws Exception {
        List<String> wire = new ArrayList<>();
        bus.setInterceptor((from, to, line) -> {
            wire.add(line);
            return Collections.singletonList(line);
        });
        SwarmEndpoint a = endpoint("botA");
        endpoint("botB");
        String secret = TestBodies.make(400);
        a.send("alpha", "botB", "task", secret);
        assertTrue(wire.size() > 1);
        for (String line : wire) {
            assertTrue(line, SwarmTransport.isSealed(line));
            assertTrue(line.length() <= a.config().sealLineBudget());
            assertFalse(line.contains("|"));
        }
    }

    @Test
    public void broadcastReachesOthersNotSender() throws Exception {
        SwarmEndpoint a = endpoint("botA");
        SwarmEndpoint b = endpoint("botB");
        SwarmEndpoint c = endpoint("botC");
        a.send("beta", SwarmFrame.BROADCAST, "hello", "hi all");
        assertEquals("hi all", b.poll().get(0).body());
        assertEquals("hi all", c.poll().get(0).body());
        assertTrue(a.poll().isEmpty());
    }

    @Test
    public void multiFrameMessageSurvivesReorderAndDuplication() throws Exception {
        List<String> held = new ArrayList<>();
        bus.setInterceptor((from, to, line) -> {
            held.add(line);
            return Collections.emptyList();
        });
        SwarmEndpoint a = endpoint("botA");
        SwarmEndpoint b = endpoint("botB");
        String text = TestBodies.make(400);
        a.send("alpha", "botB", "task", text);
        assertTrue(held.size() >= 4);
        List<SwarmMessage> got = new ArrayList<>();
        List<String> order = new ArrayList<>(held);
        Collections.reverse(order);
        for (String line : order) {
            add(got, b.receiveLine(line));
            add(got, b.receiveLine(line));
        }
        assertEquals(1, got.size());
        assertEquals(text, got.get(0).body());
        assertEquals((long) held.size(), b.rejectCount(SwarmReject.REPLAY));
    }

    private static void add(List<SwarmMessage> out, SwarmMessage m) {
        if (m != null) {
            out.add(m);
        }
    }

    @Test
    public void replayedLineIsRejected() throws Exception {
        List<String> wire = new ArrayList<>();
        bus.setInterceptor((from, to, line) -> {
            wire.add(line);
            return Collections.singletonList(line);
        });
        SwarmEndpoint a = endpoint("botA");
        SwarmEndpoint b = endpoint("botB");
        a.send("alpha", "botB", "cmd", "stop");
        assertEquals(1, b.poll().size());
        assertEquals(null, b.receiveLine(wire.get(0)));
        assertEquals(SwarmReject.REPLAY, b.lastReject());
        clock.addAndGet(5_000);
        bus.setInterceptor((from, to, line) -> {
            wire.add(line);
            return Collections.singletonList(line);
        });
        a.close();
        SwarmEndpoint a2 = new SwarmEndpoint("botA", unsignedCfg(), groups, bus.register("botA"), clock::get);
        a2.send("alpha", "botB", "cmd", "go");
        assertEquals("go", b.poll().get(0).body());
        assertEquals(null, b.receiveLine(wire.get(0)));
        assertEquals(SwarmReject.STALE_EPOCH, b.lastReject());
    }

    @Test
    public void plaintextAndForeignLinesAreRejected() throws Exception {
        SwarmEndpoint b = endpoint("botB");
        for (String line : Arrays.asList(null, "", "1|alpha|botA|botB|0|1|0|1|1/1|cmd|stop", "hello S1C.xxxx.AAAA",
                "S1C.swar.1/2.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")) {
            assertEquals(null, b.receiveLine(line));
            assertEquals(SwarmReject.UNSEALED, b.lastReject());
        }
        String frame = new SwarmFrame("alpha", "botA", "botB", 1, 1, 0, 1, 1, 1, "cmd", "stop").encode();
        assertEquals(null, b.receiveLine(SigilS1C.sealSingle(TestCircles.alphaWrongPass(), frame, 234)));
        assertEquals(SwarmReject.UNSEALED, b.lastReject());
        for (String part : SigilS1C.seal(TestCircles.alpha(), TestBodies.make(400), 234)) {
            assertEquals(null, b.receiveLine(part));
            assertEquals(SwarmReject.UNSEALED, b.lastReject());
        }
        assertEquals(null, b.receiveLine(SigilS1C.sealSingle(TestCircles.alpha(), "just chat", 234)));
        assertEquals(SwarmReject.MALFORMED, b.lastReject());
    }

    @Test
    public void defaultWireIsS2AndBothWiresInteroperate() throws Exception {
        List<String> wire = new ArrayList<>();
        bus.setInterceptor((from, to, line) -> {
            wire.add(line);
            return Collections.singletonList(line);
        });
        SwarmConfig.Builder s1 = SwarmConfig.builder();
        s1.wireVersion = "S1";
        s1.requireSignedSender = false;
        SwarmEndpoint old = new SwarmEndpoint("botOld", s1.build(), groups, bus.register("botOld"), clock::get);
        SwarmEndpoint neu = endpoint("botNew");
        neu.send("alpha", "botOld", "task", TestBodies.make(400));
        old.send("alpha", "botNew", "ack", "ok");
        List<SwarmMessage> atOld = old.poll();
        List<SwarmMessage> atNew = neu.poll();
        assertEquals(1, atOld.size());
        assertEquals(TestBodies.make(400), atOld.get(0).body());
        assertEquals(1, atNew.size());
        assertEquals("ok", atNew.get(0).body());
        for (int i = 0; i < wire.size() - 1; i++) {
            assertTrue(wire.get(i), wire.get(i).startsWith("S2C."));
        }
        assertTrue(wire.get(wire.size() - 1).startsWith("S1C."));
    }

    @Test
    public void s2SpecificRejections() throws Exception {
        SwarmEndpoint b = endpoint("botB");
        List<String> parts = SigilS2C.seal(TestCircles.alpha(), TestBodies.make(400), "", 234);
        assertTrue(parts.size() > 1);
        for (String part : parts) {
            assertTrue(SwarmTransport.isSealed(part));
            assertEquals(null, b.receiveLine(part));
            assertEquals(SwarmReject.UNSEALED, b.lastReject());
        }
        String claimsBeta = new SwarmFrame("beta", "botA", "botB", 1, 1, 0, 1, 1, 1, "cmd", "stop").encode();
        assertEquals(null, b.receiveLine(SigilCodec.sealSingle(SigilWire.S2, TestCircles.alpha(), claimsBeta, 234)));
        assertEquals(SwarmReject.WRONG_GROUP, b.lastReject());
        String ok = new SwarmFrame("alpha", "botA", "botB", 1, 1, 0, 1, 1, 1, "cmd", "stop").encode();
        assertEquals(null, b.receiveLine(SigilCodec.sealSingle(SigilWire.S2, TestCircles.alphaWrongPass(), ok, 234)));
        assertEquals(SwarmReject.UNSEALED, b.lastReject());
        SwarmMessage m = b.receiveLine(SigilCodec.sealSingle(SigilWire.S2, TestCircles.alpha(), ok, 234));
        assertEquals("stop", m.body());
        assertEquals(null, b.receiveLine(SigilS1C.sealSingle(TestCircles.alpha(), ok, 234)));
        assertEquals(SwarmReject.REPLAY, b.lastReject());
    }

    @Test
    public void groupMustMatchTheCircleThatOpenedIt() throws Exception {
        SwarmEndpoint b = endpoint("botB");
        String claimsBeta = new SwarmFrame("beta", "botA", "botB", 1, 1, 0, 1, 1, 1, "cmd", "stop").encode();
        assertEquals(null, b.receiveLine(SigilS1C.sealSingle(TestCircles.alpha(), claimsBeta, 234)));
        assertEquals(SwarmReject.WRONG_GROUP, b.lastReject());
        String unknown = new SwarmFrame("gamma", "botA", "botB", 1, 2, 0, 2, 1, 1, "cmd", "stop").encode();
        assertEquals(null, b.receiveLine(SigilS1C.sealSingle(TestCircles.alpha(), unknown, 234)));
        assertEquals(SwarmReject.WRONG_GROUP, b.lastReject());
    }

    @Test
    public void notForMeAndOwnEchoAreDropped() throws Exception {
        SwarmEndpoint b = endpoint("botB");
        String forC = new SwarmFrame("alpha", "botA", "botC", 1, 1, 0, 1, 1, 1, "cmd", "x").encode();
        assertEquals(null, b.receiveLine(SigilS1C.sealSingle(TestCircles.alpha(), forC, 234)));
        assertEquals(SwarmReject.NOT_FOR_ME, b.lastReject());
        String own = new SwarmFrame("alpha", "botB", "*", 1, 1, 0, 1, 1, 1, "cmd", "x").encode();
        assertEquals(null, b.receiveLine(SigilS1C.sealSingle(TestCircles.alpha(), own, 234)));
        assertEquals(SwarmReject.SELF, b.lastReject());
    }

    @Test
    public void missingFrameCountsAsExpired() throws Exception {
        bus.setInterceptor(new InMemorySwarmBus.Interceptor() {
            int n;
            @Override
            public List<String> deliver(String from, String to, String line) {
                return ++n == 2 ? Collections.<String>emptyList() : Collections.singletonList(line);
            }
        });
        SwarmEndpoint a = endpoint("botA");
        SwarmEndpoint b = endpoint("botB");
        a.send("alpha", "botB", "task", TestBodies.make(400));
        assertTrue(b.poll().isEmpty());
        assertEquals(1, b.pendingMessages());
        clock.addAndGet(unsignedCfg().reassemblyTimeoutMs());
        assertTrue(b.poll().isEmpty());
        assertEquals(0, b.pendingMessages());
        assertEquals(1L, b.rejectCount(SwarmReject.EXPIRED));
    }

    @Test
    public void sendSideRefusals() throws Exception {
        SwarmEndpoint a = endpoint("botA");
        try {
            a.send("gamma", "botB", "t", "x");
            fail();
        } catch (SwarmFrameException e) {
            assertEquals(SwarmReject.WRONG_GROUP, e.reason());
        }
        try {
            a.send("alpha", "botB", "t", TestBodies.make(5000));
            fail();
        } catch (SwarmFrameException e) {
            assertEquals(SwarmReject.TOO_LARGE, e.reason());
        }
        try {
            bus.register("botZ").send("*", "plaintext is not allowed");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            new SwarmEndpoint("botQ", unsignedCfg(), Collections.<String, SigilCircle>emptyMap(),
                    bus.register("botQ"), clock::get);
            fail("no plaintext mode");
        } catch (IllegalArgumentException expected) {
        }
        Map<String, SigilCircle> shared = new LinkedHashMap<>();
        shared.put("g1", TestCircles.alpha());
        shared.put("g2", TestCircles.alpha());
        try {
            new SwarmEndpoint("botR", unsignedCfg(), shared, bus.register("botR"), clock::get);
            fail("groups sharing a circle could impersonate each other");
        } catch (IllegalArgumentException expected) {
        }
    }
}
