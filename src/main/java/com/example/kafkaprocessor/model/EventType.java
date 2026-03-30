package com.example.kafkaprocessor.model;

/**
 * Well-known values for {@link EventHeader#eventType()}.
 *
 * <p>Backdated Endorsements are identified by {@code eventType == END} combined with
 * {@link EventHeader#backdated()} {@code == true} — they are not a separate event type code.
 */
public final class EventType {

    /** New Business */
    public static final String NC  = "NC";

    /** Endorsement. When {@link EventHeader#backdated()} is {@code true}, this is a Backdated Endorsement. */
    public static final String END = "END";

    /** Termination */
    public static final String TRM = "TRM";

    /** Renewal */
    public static final String RNW = "RNW";

    /** Siphon event type — forwarded as-is to the siphon topic on the consumer thread; bypasses all processing. */
    public static final String BDE = "BDE";

    private EventType() {}
}
