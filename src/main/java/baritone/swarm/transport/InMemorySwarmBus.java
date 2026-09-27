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


package baritone.swarm.transport;

import baritone.swarm.frame.SwarmFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-process transport for tests and simulation. Each registered member gets
 * a queue; broadcasts go to every member but the sender. An optional
 * {@link Interceptor} can drop, duplicate, reorder or tamper with deliveries.
 */
public final class InMemorySwarmBus {

    /** Decides what actually lands in {@code recipient}'s queue for one delivery. */
    public interface Interceptor {
        List<String> deliver(String from, String recipient, String line);
    }

    private final Map<String, List<String>> queues = new LinkedHashMap<>();
    private Interceptor interceptor = (from, to, line) -> Collections.singletonList(line);
    private long undeliverable;

    public synchronized void setInterceptor(Interceptor interceptor) {
        this.interceptor = interceptor;
    }

    /** Lines addressed to members that are not registered (like chat to an offline player). */
    public synchronized long undeliverable() {
        return undeliverable;
    }

    public synchronized SwarmTransport register(String id) {
        if (!SwarmFrame.isValidId(id)) {
            throw new IllegalArgumentException("bad member id");
        }
        if (queues.containsKey(id)) {
            throw new IllegalArgumentException("already registered: " + id);
        }
        queues.put(id, new ArrayList<>());
        return new Endpoint(id);
    }

    private synchronized void send(String from, String recipient, String line) {
        SwarmTransport.requireSealed(line);
        if (SwarmTransport.BROADCAST.equals(recipient)) {
            for (Map.Entry<String, List<String>> e : queues.entrySet()) {
                if (!e.getKey().equals(from)) {
                    e.getValue().addAll(interceptor.deliver(from, e.getKey(), line));
                }
            }
            return;
        }
        List<String> q = queues.get(recipient);
        if (q == null) {
            undeliverable++;
            return;
        }
        q.addAll(interceptor.deliver(from, recipient, line));
    }

    private synchronized List<String> drain(String id) {
        List<String> q = queues.get(id);
        if (q == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(q);
        q.clear();
        return out;
    }

    private synchronized void unregister(String id) {
        queues.remove(id);
    }

    private final class Endpoint implements SwarmTransport {
        private final String id;

        Endpoint(String id) {
            this.id = id;
        }

        @Override
        public String selfId() {
            return id;
        }

        @Override
        public void send(String recipient, String sealedLine) {
            InMemorySwarmBus.this.send(id, recipient, sealedLine);
        }

        @Override
        public List<String> receive() {
            return drain(id);
        }

        @Override
        public void close() {
            unregister(id);
        }
    }
}
