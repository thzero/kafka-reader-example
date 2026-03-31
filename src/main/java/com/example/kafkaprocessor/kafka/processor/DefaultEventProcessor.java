package com.example.kafkaprocessor.kafka.processor;

import com.example.kafkaprocessor.kafka.KafkaProducerService;
import com.example.kafkaprocessor.model.KafkaMessage;
import com.fasterxml.jackson.databind.JsonNode;
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
 *     public void process(KafkaMessage message) {
 *         // fetch data, build output object...
 *         publisher.publish(message.body().messageId(), outputObject, outputTopic);
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
    public void process(KafkaMessage message) {
        String messageId = message.body() != null ? message.body().messageId() : null;
        log.info("Publishing processed message messageId={} to topic={}", messageId, outputTopic);
        JsonNode payload = objectMapper.valueToTree(message);
        publisher.publish(messageId, payload, outputTopic);
    }
}
