package com.example.kafkaprocessor.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Unified Kafka publisher.
 * Used by both the siphon fast-path in {@link KafkaConsumerListener} and by
 * {@link com.example.kafkaprocessor.kafka.processor.EventProcessor} implementations.
 *
 * <p>Two overloads are provided:
 * <ul>
 *   <li>{@link #publish(String, String, String)} — for pre-serialized strings (e.g. siphon pass-through)</li>
 *   <li>{@link #publish(String, JsonNode, String)} — for dynamic JSON objects; serialized by Kafka's JsonSerializer</li>
 * </ul>
 */
@Service
public class KafkaProducerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTemplate<String, JsonNode> jsonKafkaTemplate;

    public KafkaProducerService(
            @Qualifier("kafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            @Qualifier("jsonKafkaTemplate") KafkaTemplate<String, JsonNode> jsonKafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonKafkaTemplate = jsonKafkaTemplate;
    }

    /**
     * Publishes a pre-serialized JSON string to the specified Kafka topic.
     * Use this overload for pass-through payloads (e.g. the siphon fast-path).
     *
     * @param key     Kafka record key (typically the messageId) — used for partition routing
     * @param payload raw JSON string to publish as the record value
     * @param topic   target Kafka topic
     * @throws KafkaPublishException if the send fails
     */
    public void publish(String key, String payload, String topic) {
        kafkaTemplate.executeInTransaction(ops -> {
            ops.send(topic, key, payload).whenComplete((result, ex) -> {
                if (ex != null) {
                    throw new KafkaPublishException(
                            "Failed to publish to topic=" + topic + " key=" + key, ex);
                }
            });
            return null;
        });
        log.info("Published to topic={} key={}", topic, key);
    }

    /**
     * Publishes a dynamic JSON object to the specified Kafka topic.
     * Uses a dedicated {@link KafkaTemplate} backed by Jackson's {@code JsonSerializer}.
     * No manual serialization needed — pass the {@link JsonNode} directly.
     *
     * @param key     Kafka record key (typically the messageId) — used for partition routing
     * @param payload JSON node to publish as the record value
     * @param topic   target Kafka topic
     * @throws KafkaPublishException if the send fails
     */
    public void publish(String key, JsonNode payload, String topic) {
        jsonKafkaTemplate.executeInTransaction(ops -> {
            ops.send(topic, key, payload).whenComplete((result, ex) -> {
                if (ex != null) {
                    throw new KafkaPublishException(
                            "Failed to publish to topic=" + topic + " key=" + key, ex);
                }
            });
            return null;
        });
        log.info("Published to topic={} key={}", topic, key);
    }
}
