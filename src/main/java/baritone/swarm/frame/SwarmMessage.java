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


package baritone.swarm.frame;

/** A fully reassembled, authenticated, replay-checked swarm message. */
public final class SwarmMessage {

    private final String group;
    private final String from;
    private final String to;
    private final long epoch;
    private final long msgId;
    private final String type;
    private final String body;

    public SwarmMessage(String group, String from, String to, long epoch, long msgId, String type, String body) {
        this.group = group;
        this.from = from;
        this.to = to;
        this.epoch = epoch;
        this.msgId = msgId;
        this.type = type;
        this.body = body;
    }

    public String group() { return group; }
    public String from() { return from; }
    /** Recipient id, or {@link SwarmFrame#BROADCAST}. */
    public String to() { return to; }
    public long epoch() { return epoch; }
    public long msgId() { return msgId; }
    public String type() { return type; }
    public String body() { return body; }

    @Override
    public String toString() {
        return "SwarmMessage{" + group + " " + from + "->" + to + " e=" + epoch + " m=" + msgId
                + " type=" + type + " bodyChars=" + body.length() + "}";
    }
}
