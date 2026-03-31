package com.example.kafkaprocessor.kafka.processor;

import com.example.kafkaprocessor.kafka.KafkaProducerService;
import com.example.kafkaprocessor.kafka.ProcessingException;
import com.example.kafkaprocessor.model.KafkaMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Default / fallback event processor — handles all event types that have no dedicated processor.
 *
 * <p>Serializes the incoming {@link KafkaMessage} back to JSON and publishes it to the configured
 * output topic. This is a pass-through stub; replace or supplement with event-specific processors
 * by implementing {@link EventProcessor} with a non-{@code "*"} eventCode.
 *
 * <p>To add a processor for a specific event type, e.g. NC:
 * <pre>{@code
 * @Component
 * public class NcEventProcessor implements EventProcessor {
 *     private final KafkaProducerService publisher;
 *
 *     @Value("${kafka.topic.output}")
 *     private String outputTopic;
 *
 *     @Override public String eventCode() { return "NC"; }
 *
 *     @Override
 *     public void process(KafkaMessage message, String rawPayload) {
 *         // transform message, build output JSON...
 *         publisher.publish(message.body().messageId(), outputJson, outputTopic);
 *     }
 * }
 * }</pre>
 */
@Component
public class DefaultEventProcessor implements EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(DefaultEventProcessor.class);

    private final KafkaProducerService publisher;
    private final ObjectMapper objectMapper;

    @Value("${kafka.topic.output}")
    private String outputTopic;

    public DefaultEventProcessor(KafkaProducerService publisher, ObjectMapper objectMapper) {
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventCode() {
        return "*";
    }

    @Override
    public void process(KafkaMessage message, String rawPayload) {
        String messageId = message.body() != null ? message.body().messageId() : null;
        String outputJson;
        try {
            outputJson = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new ProcessingException("Failed to serialize output message for messageId=" + messageId, e);
        }
        log.info("Publishing processed message messageId={} to topic={}", messageId, outputTopic);
        publisher.publish(messageId, outputJson, outputTopic);
    }
}
