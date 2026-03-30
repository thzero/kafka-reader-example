package com.example.kafkaprocessor.kafka;

import com.example.kafkaprocessor.model.KafkaMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaProducerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerService.class);

    private final KafkaTemplate<String, KafkaMessage> kafkaTemplate;

    @Value("${kafka.topic.output}")
    private String outputTopic;

    public KafkaProducerService(KafkaTemplate<String, KafkaMessage> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(KafkaMessage message) {
        String messageId = message.body() != null ? message.body().messageId() : null;
        String interactionId = message.event() != null ? message.event().interactionId() : null;

        kafkaTemplate.executeInTransaction(ops -> {
            ops.send(outputTopic, messageId, message).whenComplete((result, ex) -> {
                if (ex != null) {
                    throw new KafkaPublishException("Failed to publish message: " + messageId, ex);
                }
            });
            return null;
        });

        log.info("Publish succeeded messageId={} interactionId={}", messageId, interactionId);
    }

    public void siphon(KafkaMessage message, String topic) {
        String messageId = message.body() != null ? message.body().messageId() : null;
        String interactionId = message.event() != null ? message.event().interactionId() : null;

        kafkaTemplate.executeInTransaction(ops -> {
            ops.send(topic, messageId, message).whenComplete((result, ex) -> {
                if (ex != null) {
                    throw new KafkaPublishException("Failed to siphon message: " + messageId, ex);
                }
            });
            return null;
        });

        log.info("Siphon succeeded topic={} messageId={} interactionId={}", topic, messageId, interactionId);
    }
}
