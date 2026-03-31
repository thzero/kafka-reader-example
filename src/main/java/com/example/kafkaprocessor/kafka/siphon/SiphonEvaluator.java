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
 * <p>Active evaluators are filtered by {@code app.siphon.enabled} (list of event codes
 * matching {@link #eventCode()}). An empty list activates all registered evaluators.
 *
 * <p>Register new implementations as {@code @Component} beans.
 * {@code KafkaConsumerListener} iterates the active list in order; the first non-empty
 * result ends evaluation.
 */
public interface SiphonEvaluator {

    /**
     * Short identifier for this evaluator, e.g. {@code "bde"}.
     * Must match the corresponding entry in {@code app.siphon.enabled}.
     */
    String eventCode();

    /**
     * Returns the Kafka topic name to siphon this message to, or {@link Optional#empty()}
     * if the message should proceed through the normal processing pipeline.
     *
     * <p>Called on the Kafka consumer thread after deserialization — must be fast
     * (no I/O, no locking) and must not mutate the message.
     */
    Optional<String> evaluate(KafkaMessage message);
}
