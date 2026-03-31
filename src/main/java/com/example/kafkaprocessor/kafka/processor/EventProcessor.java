package com.example.kafkaprocessor.kafka.processor;

import com.example.kafkaprocessor.model.KafkaMessage;

/**
 * Strategy interface for per-event-type processing.
 *
 * <p>Implementations are responsible for both transforming the message and publishing the result
 * to Kafka via {@link com.example.kafkaprocessor.kafka.KafkaProducerService#publish}.
 *
 * <p>Register a new processor by implementing this interface and annotating the class with
 * {@code @Component}. The eventCode must match the {@code event.eventType} values in incoming
 * messages (e.g., {@code "NC"}, {@code "END"}, {@code "TRM"}, {@code "RNW"}).
 * Use {@code "*"} as the eventCode to register a default/fallback processor.
 *
 * <p>Thrown exceptions are interpreted by {@link com.example.kafkaprocessor.kafka.KafkaConsumerListener}:
 * <ul>
 *   <li>{@link com.example.kafkaprocessor.kafka.KafkaPublishException} → {@code PUBLISH_ERROR} dead letter</li>
 *   <li>Any other exception → {@code PROCESSING_ERROR} dead letter</li>
 * </ul>
 */
public interface EventProcessor {

    /**
     * Returns the eventType code this processor handles, or {@code "*"} for the default processor.
     * Must be unique across all registered processors (excluding the single default).
     */
    String eventCode();

    /**
     * Process the incoming message and publish the result to Kafka.
     *
     * <p>Implementations are responsible for fetching any additional data needed
     * to produce the output, building the output payload, and calling
     * {@link com.example.kafkaprocessor.kafka.KafkaProducerService#publish}.
     *
     * @param message the deserialized incoming message
     * @throws com.example.kafkaprocessor.kafka.KafkaPublishException if the publish step fails
     * @throws com.example.kafkaprocessor.kafka.ProcessingException   if business logic fails
     */
    void process(KafkaMessage message);
}
