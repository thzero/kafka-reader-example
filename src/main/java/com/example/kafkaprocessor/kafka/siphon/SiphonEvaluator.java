package com.example.kafkaprocessor.kafka.siphon;

import com.example.kafkaprocessor.model.KafkaMessage;

import java.util.Optional;

/**
 * Strategy interface for deciding whether an incoming message should be siphoned
 * (forwarded as-is to a siphon topic without entering the processing pipeline).
 *
 * <p>Each implementation owns its own topic routing: {@link #evaluate} returns the
 * target topic name when the message should be siphoned, or {@link Optional#empty()}
 * to let it proceed through the normal pipeline.
 *
 * <p>Multiple siphon topics are supported by implementing different evaluators — each
 * returns a different topic name for the messages it matches.
 *
 * <p>Replace or extend the default implementation ({@link BackdatedEndorsementSiphonEvaluator})
 * by supplying a different {@code @Primary} or {@code @Qualifier}-targeted Spring bean.
 */
public interface SiphonEvaluator {

    /**
     * Returns the Kafka topic name to siphon this message to, or {@link Optional#empty()}
     * if the message should proceed through the normal processing pipeline.
     *
     * <p>Called on the Kafka consumer thread after deserialization — must be fast
     * (no I/O, no locking) and must not mutate the message.
     */
    Optional<String> evaluate(KafkaMessage message);
}
