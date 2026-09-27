package baritone.behavior;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.event.events.TickEvent;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.swarm.SwarmConfig;
import baritone.swarm.SwarmControl;
import baritone.swarm.SwarmEndpoint;
import baritone.swarm.SwarmKeys;
import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.ChatSwarmTransport;
import baritone.swarm.transport.SwarmChannel;
import baritone.swarm.transport.SwarmRateLimiter;
import net.minecraft.client.entity.player.ClientPlayerEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Runs the swarm link when swarmEnabled. */
public final class SwarmBehavior extends Behavior implements Helper {

    private static final class Link {
        final String self;
        final ChatSwarmTransport transport;
        final SwarmControl control;

        Link(String self, ChatSwarmTransport transport, SwarmControl control) {
            this.self = self;
            this.transport = transport;
            this.control = control;
        }
    }

    private Link link;
    private CompletableFuture<Link> loading;
    private String loadingFor;
    private String failure;

    public SwarmBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        try {
            tick(event);
        } catch (Throwable t) {
            failure = "tick failed: " + t;
            stop();
        }
    }

    private void tick(TickEvent event) {
        Settings s = Baritone.settings();
        if (!s.swarmEnabled.value) {
            if (link != null || loading != null) {
                stop();
            }
            failure = null;
            return;
        }
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }
        String self = ctx.player().getGameProfile().getName();
        if (link != null && !link.self.equals(self)) {
            stop();
        }
        if (loading != null) {
            if (!loading.isDone()) {
                return;
            }
            try {
                link = loading.join();
                failure = null;
                logDirect("swarm: online as " + self + " (" + link.control.groupCount() + " group(s), "
                        + link.control.endpoint().config().wire() + "); see #swarm status");
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                failure = c.getMessage() == null ? c.toString() : c.getMessage();
                logDirect("swarm: not started: " + failure);
            }
            loading = null;
            loadingFor = null;
        }
        if (link == null) {
            if (failure == null) {
                start(self, s);
            }
            return;
        }
        link.control.tick();
        ClientPlayerEntity player = ctx.player();
        link.transport.flush(nowMs(), player::sendChatMessage);
    }

    private void start(String self, Settings s) {
        Path dir = baritone.getDirectory();
        String rosterFile = s.swarmRosterFile.value;
        String home = s.swarmSigilHome.value;
        String env = System.getenv("SIGIL_HOME");
        SwarmConfig.Builder cfg = SwarmConfig.builderFromSettings(s);
        SwarmChannel channel = SwarmChannel.parse(s.swarmChannel.value);
        String template = s.swarmCommandTemplate.value;
        double rate = s.swarmSendRatePerSec.value;
        int burst = s.swarmSendBurst.value;
        boolean unsafe = s.swarmAllowUnsafeRate.value;
        int queueMax = s.swarmSendQueueMax.value;
        loadingFor = self;
        loading = CompletableFuture.supplyAsync(() -> {
            try {
                Path rp = Paths.get(rosterFile);
                if (!rp.isAbsolute()) {
                    rp = dir.resolve(rosterFile);
                }
                if (!Files.isRegularFile(rp)) {
                    throw new IllegalStateException("no roster at " + rp);
                }
                SwarmRoster roster = SwarmRoster.parse(new String(Files.readAllBytes(rp), StandardCharsets.UTF_8));
                Path sigilHome = SwarmKeys.sigilHome(home, env);
                Map<String, SigilCircle> circles = SwarmKeys.circlesFor(roster, self, sigilHome);
                SwarmRateLimiter limiter = new SwarmRateLimiter(rate, burst, unsafe, queueMax, nowMs());
                ChatSwarmTransport transport = new ChatSwarmTransport(self, channel, template, roster, limiter,
                        cfg.maxLineChars);
                for (SwarmRoster.Group g : roster.groupsOf(self)) {
                    int overhead = ChatSwarmTransport.templateOverhead(transport.templateFor(transport.channelFor(g.id())));
                    cfg.lineReserveChars = Math.max(cfg.lineReserveChars, overhead);
                }
                SwarmEndpoint endpoint = new SwarmEndpoint(self, cfg.build(), circles, transport,
                        System::currentTimeMillis);
                endpoint.setMemberCheck(roster::isMember);
                SwarmKeys.bindSigning(endpoint, self, sigilHome);
                SwarmControl control = new SwarmControl(roster, endpoint, new Status(), System::currentTimeMillis,
                        this::logDirect, () -> "queued " + limiter.queued() + ", sent " + limiter.sent() + ", dropped "
                        + limiter.dropped() + ", too long " + transport.tooLong() + String.format(", rate %.2f/s burst %d",
                        limiter.rate(), limiter.burst()));
                return new Link(self, transport, control);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private void stop() {
        if (loading != null) {
            loading.cancel(false);
        }
        loading = null;
        loadingFor = null;
        link = null;
    }

    public void reload() {
        stop();
        failure = null;
    }

    public void onIncomingChat(String text) {
        try {
            Link l = link;
            if (l != null && text != null) {
                l.transport.onChat(text);
            }
        } catch (Throwable t) {
        }
    }

    public SwarmControl control() {
        return link == null ? null : link.control;
    }

    public String state() {
        if (link != null) {
            return null;
        }
        if (!Baritone.settings().swarmEnabled.value) {
            return "swarmEnabled is false";
        }
        if (loading != null) {
            return "loading roster and keyring for " + loadingFor;
        }
        return failure != null ? "not started: " + failure : "waiting for a world";
    }

    public List<String> statusLines() {
        Link l = link;
        return l == null ? Collections.singletonList("swarm: " + state()) : l.control.statusLines();
    }

    private final class Status implements SwarmControl.LocalStatus {
        @Override
        public String name() {
            return ctx.player() == null ? "?" : ctx.player().getGameProfile().getName();
        }

        @Override
        public String position() {
            if (ctx.player() == null) {
                return "?";
            }
            BetterBlockPos p = ctx.playerFeet();
            return p.x + "," + p.y + "," + p.z;
        }

        @Override
        public String process() {
            return baritone.getPathingControlManager().mostRecentInControl()
                    .map(IBaritoneProcess::displayName).orElse("idle");
        }
    }
}
