package com.example.kafkaprocessor.kafka.siphon;

import com.example.kafkaprocessor.model.KafkaMessage;

/**
 * Strategy interface for deciding whether an incoming message should be siphoned
 * (forwarded as-is to {@code kafka.topic.siphon} without entering the processing pipeline).
 *
 * <p>Replace or extend the default implementation ({@link BackdatedEndorsementSiphonEvaluator})
 * by supplying a different {@code @Primary} or {@code @Qualifier}-targeted Spring bean.
 */
public interface SiphonEvaluator {

    /**
     * Returns {@code true} if {@code message} should be siphoned on the consumer thread.
     * <p>This method is called on the Kafka consumer thread after deserialization; it must be
     * fast (no I/O, no locking) and must not mutate the message.
     */
    boolean shouldSiphon(KafkaMessage message);
}
