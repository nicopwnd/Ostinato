package baritone.swarm.crypto;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SigilS2STest {

    private static SigilCircle circle;
    private static SigilEd25519 alice;
    private static SigilEd25519 mallory;
    private static List<SigilCircle> ring;
    private static List<SigilEd25519> pins;

    @BeforeClass
    public static void derive() throws Exception {
        circle = SigilCircle.derive("s2s-java-test", "public-s2s-test-passphrase-DO-NOT-USE");
        alice = SigilEd25519.testSignet("alice-DO-NOT-USE");
        mallory = SigilEd25519.testSignet("mallory-DO-NOT-USE");
        ring = Collections.singletonList(circle);
        pins = Collections.singletonList(alice);
    }

    @Test
    public void roundTripSingle() throws Exception {
        String token = SigilS2S.sealSingle(circle, alice, "ping builders", 256);
        assertTrue(token.startsWith("S2S." + circle.slug() + "."));
        assertEquals("ping builders", SigilS2S.open(token, ring, pins).text);
    }

    @Test
    public void unsignedRelabelFails() throws Exception {
        String fake = "S2S" + SigilS2C.sealSingle(circle, "unsigned circle message", 256).substring(3);
        try {
            SigilS2S.open(fake, ring, pins);
            fail("unsigned relabel opened");
        } catch (SigilException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
    }

    @Test
    public void unknownSignerRefused() throws Exception {
        String token = SigilS2S.sealSingle(circle, mallory, "impersonation", 256);
        try {
            SigilS2S.open(token, ring, pins);
            fail("mallory accepted");
        } catch (SigilException expected) {
            assertTrue(expected.getMessage().contains("pinned"));
        }
    }

    @Test
    public void codecFindsS2SToken() throws Exception {
        String token = SigilS2S.sealSingle(circle, alice, "hi", 256);
        assertEquals(token, SigilCodec.extractToken("Steve whispers to you: " + token));
    }
}
