package baritone.swarm.crypto;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SigilSignetsTest {

    @Test
    public void publicOnly() throws Exception {
        SigilEd25519 k = SigilEd25519.testSignet("botA-DO-NOT-USE");
        String json = "{\"kind\":\"signet\",\"name\":\"botA\",\"public\":\""
                + SigilB64.encode(k.publicKey()) + "\"}";
        SigilSignets.Record r = SigilSignets.parse(json);
        assertEquals("botA", r.name);
        assertArrayEquals(k.publicKey(), r.key.publicKey());
        try {
            r.key.sign(new byte[] {1});
            fail("public-only must not sign");
        } catch (SigilException expected) {
        }
    }

    @Test
    public void seedMustMatchPublic() throws Exception {
        SigilEd25519 a = SigilEd25519.testSignet("botA-DO-NOT-USE");
        SigilEd25519 b = SigilEd25519.testSignet("botB-DO-NOT-USE");
        String json = "{\"kind\":\"signet\",\"name\":\"x\",\"seed\":\""
                + SigilB64.encode(a.publicKey()) + "\",\"public\":\""
                + SigilB64.encode(b.publicKey()) + "\"}";
        try {
            SigilSignets.parse(json);
            fail();
        } catch (SigilException expected) {
        }
    }
}
