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

/** Why an inbound line or outbound message was refused. Counted per endpoint. */
public enum SwarmReject {
    /** Not a single-line S1C/S2C token, or no configured circle could open it. */
    UNSEALED,
    /** Opened, but the plaintext is not a valid swarm frame. */
    MALFORMED,
    /** The frame names a group whose circle is not the one that opened it. */
    WRONG_GROUP,
    /** Our own frame echoed back (broadcast loopback). */
    SELF,
    /** Signed (S2S) by a pinned signet that belongs to another member than the frame's sender. */
    WRONG_SIGNER,
    /** Sender is not a member of the group it claims (roster check). */
    NOT_MEMBER,
    /** Addressed to another member. */
    NOT_FOR_ME,
    /** Sequence number already seen, or older than the replay window. */
    REPLAY,
    /** Sender epoch lower than one already seen from that sender. */
    STALE_EPOCH,
    /** Frame timestamp outside {@code swarmMaxClockSkewSec} (only when enabled). */
    CLOCK_SKEW,
    /** Too many distinct senders tracked ({@code swarmMaxPeers}). */
    PEER_LIMIT,
    /** A part whose seq/total/type/recipient does not belong to the message id it claims. */
    SPLICE,
    /** Part already received for this message. */
    DUPLICATE,
    /** Too many partially received messages ({@code swarmMaxPendingMessages}). */
    OVERFLOW,
    /** Message did not complete within {@code swarmReassemblyTimeoutMs} (missing parts). */
    EXPIRED,
    /** Outbound message needs more than {@code swarmMaxChunks} frames. */
    TOO_LARGE
}
