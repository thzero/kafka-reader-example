package com.example.kafkametrics.model;

/**
 * Well-known values for {@link EventHeader#eventType()}.
 *
 * <p>Backdated Endorsements are identified by {@code eventType == END} combined with
 * {@link EventHeader#backdated()} {@code == true} — they are not a separate event type code.
 * Backdated Endorsements are siphoned directly to {@code kafka.topic.siphon} on the consumer thread.
 */
public final class EventType {

    /** New Business */
    public static final String NC = "NC";

    /** Endorsement. When {@link EventHeader#backdated()} is {@code true}, this is a Backdated Endorsement
     *  and will be siphoned directly to the siphon topic on the consumer thread. */
    public static final String END = "END";

    /** Termination */
    public static final String TRM = "TRM";

    /** Renewal */
    public static final String RNW = "RNW";

    private EventType() {}
}
