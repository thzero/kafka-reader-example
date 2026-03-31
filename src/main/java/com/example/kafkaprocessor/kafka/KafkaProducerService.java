package com.example.kafkaprocessor.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Unified Kafka publisher. Accepts a raw JSON payload string and a target topic.
 * Used by both the siphon fast-path in {@link KafkaConsumerListener} and by
 * {@link com.example.kafkaprocessor.kafka.processor.EventProcessor} implementations.
 */
@Service
public class KafkaProducerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaProducerService(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes a JSON payload to the specified Kafka topic within the current transaction.
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
}
