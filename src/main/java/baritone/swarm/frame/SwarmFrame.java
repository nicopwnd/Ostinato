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

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One swarm frame: the plaintext sealed inside a single S1C token.
 *
 * <pre>
 * 1|group|from|to|epoch|seq|ts|msg|i/n|type|body
 * </pre>
 *
 * <ul>
 *   <li>{@code group}, {@code from}, {@code to}: {@code [A-Za-z0-9_-]{1,16}}; {@code to} may be {@code *} (broadcast).</li>
 *   <li>{@code epoch}: sender's start time in seconds; only compared with the same sender's earlier epochs.</li>
 *   <li>{@code seq}: per-sender frame counter within the epoch (replay protection).</li>
 *   <li>{@code ts}: sender wall clock in seconds; only checked when {@code swarmMaxClockSkewSec > 0}.</li>
 *   <li>{@code msg}: message id = seq of the message's first frame, so every part must satisfy
 *       {@code seq == msg + i - 1}. This binds parts to their message: a part cannot be relabelled into
 *       another message without failing that check, and the whole frame is under the GCM tag.</li>
 *   <li>{@code epoch}, {@code seq}, {@code ts}, {@code msg}: lowercase base36. {@code i/n}: decimal.</li>
 *   <li>{@code type}: {@code [A-Za-z0-9_]{1,8}}. {@code body}: the rest of the text (may contain {@code |}).</li>
 * </ul>
 */
public final class SwarmFrame {

    public static final String VERSION = "1";
    public static final String BROADCAST = "*";
    /** Absolute cap on parts per message regardless of settings. */
    public static final int MAX_TOTAL = 999;

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final Pattern TYPE = Pattern.compile("[A-Za-z0-9_]{1,8}");
    private static final Pattern B36 = Pattern.compile("[0-9a-z]{1,13}");
    private static final Pattern DEC = Pattern.compile("[1-9][0-9]{0,2}");

    private final String group;
    private final String from;
    private final String to;
    private final long epoch;
    private final long seq;
    private final long ts;
    private final long msgId;
    private final int index;
    private final int total;
    private final String type;
    private final String body;

    public SwarmFrame(String group, String from, String to, long epoch, long seq, long ts, long msgId,
                      int index, int total, String type, String body) throws SwarmFrameException {
        requireId("group", group);
        requireId("from", from);
        if (!BROADCAST.equals(to)) {
            requireId("to", to);
        }
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new SwarmFrameException(SwarmReject.MALFORMED, "bad type");
        }
        if (epoch < 0 || seq < 1 || ts < 0 || msgId < 1) {
            throw new SwarmFrameException(SwarmReject.MALFORMED, "negative counter");
        }
        if (total < 1 || total > MAX_TOTAL || index < 1 || index > total) {
            throw new SwarmFrameException(SwarmReject.MALFORMED, "bad part " + index + "/" + total);
        }
        if (seq - msgId != index - 1) {
            throw new SwarmFrameException(SwarmReject.SPLICE, "seq " + seq + " is not part " + index + " of msg " + msgId);
        }
        if (body == null) {
            throw new SwarmFrameException(SwarmReject.MALFORMED, "null body");
        }
        this.group = group;
        this.from = from;
        this.to = to;
        this.epoch = epoch;
        this.seq = seq;
        this.ts = ts;
        this.msgId = msgId;
        this.index = index;
        this.total = total;
        this.type = type;
        this.body = body;
    }

    private static void requireId(String what, String v) throws SwarmFrameException {
        if (v == null || !ID.matcher(v).matches()) {
            throw new SwarmFrameException(SwarmReject.MALFORMED, "bad " + what);
        }
    }

    /** Whether {@code id} is a valid member/group id (also safe as a file name). */
    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public String encode() {
        return header(group, from, to, epoch, seq, ts, msgId, index, total, type) + body;
    }

    /** UTF-8 length of {@link #encode()}. */
    public int encodedBytes() {
        return encode().getBytes(StandardCharsets.UTF_8).length;
    }

    static String header(String group, String from, String to, long epoch, long seq, long ts, long msgId,
                         int index, int total, String type) {
        return VERSION + '|' + group + '|' + from + '|' + to + '|' + b36(epoch) + '|' + b36(seq) + '|' + b36(ts)
                + '|' + b36(msgId) + '|' + index + '/' + total + '|' + type + '|';
    }

    private static String b36(long v) {
        return Long.toString(v, 36).toLowerCase(Locale.ROOT);
    }

    public static SwarmFrame decode(String text) throws SwarmFrameException {
        if (text == null) {
            throw new SwarmFrameException(SwarmReject.MALFORMED, "null");
        }
        String[] f = text.split("\\|", 11);
        if (f.length != 11 || !VERSION.equals(f[0])) {
            throw new SwarmFrameException(SwarmReject.MALFORMED, "not a v" + VERSION + " frame");
        }
        int slash = f[8].indexOf('/');
        if (slash < 0) {
            throw new SwarmFrameException(SwarmReject.MALFORMED, "bad part field");
        }
        String i = f[8].substring(0, slash);
        String n = f[8].substring(slash + 1);
        if (!DEC.matcher(i).matches() || !DEC.matcher(n).matches()) {
            throw new SwarmFrameException(SwarmReject.MALFORMED, "bad part field");
        }
        return new SwarmFrame(f[1], f[2], f[3], parseB36(f[4]), parseB36(f[5]), parseB36(f[6]), parseB36(f[7]),
                Integer.parseInt(i), Integer.parseInt(n), f[9], f[10]);
    }

    private static long parseB36(String s) throws SwarmFrameException {
        if (!B36.matcher(s).matches()) {
            throw new SwarmFrameException(SwarmReject.MALFORMED, "bad base36 field");
        }
        try {
            return Long.parseLong(s, 36);
        } catch (NumberFormatException e) {
            throw new SwarmFrameException(SwarmReject.MALFORMED, "base36 overflow");
        }
    }

    public String group() { return group; }
    public String from() { return from; }
    public String to() { return to; }
    public long epoch() { return epoch; }
    public long seq() { return seq; }
    public long ts() { return ts; }
    public long msgId() { return msgId; }
    public int index() { return index; }
    public int total() { return total; }
    public String type() { return type; }
    public String body() { return body; }

    @Override
    public String toString() {
        return "SwarmFrame{" + group + " " + from + "->" + to + " e=" + epoch + " seq=" + seq + " msg=" + msgId
                + " " + index + "/" + total + " " + type + "}";
    }
}
