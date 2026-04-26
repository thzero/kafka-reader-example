package com.example.kafkametrics.processor;

import com.example.kafkametrics.kafka.KafkaProducerService;
import com.example.kafkametrics.model.EventHeader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default / fallback event processor — handles all event types that have no dedicated processor.
 *
 * <p>Serializes the incoming {@link KafkaMessage} back to JSON and publishes it to the configured
 * output topic. This is a pass-through stub; replace or supplement with event-specific processors
 * by extending {@link AbstractEventProcessor} with a non-{@code "*"} eventCode.
 *
 * <p>To add a processor for a specific event type, e.g. NC:
 * <pre>{@code
 * @Component
 * public class NcEventProcessor extends AbstractEventProcessor<NcOutput> {
 *
 *     @Override public String eventCode() { return "NC"; }
 *
 *     @Override
 *     protected NcOutput processInternal(KafkaMessage message, String messageId) {
 *         // fetch data, build output object...
 *         return new NcOutput(...);
 *     }
 * }
 * }</pre>
 */
@Component
public class DefaultEventProcessor extends AbstractEventProcessor<JsonNode> {

    private static final Logger log = LoggerFactory.getLogger(DefaultEventProcessor.class);

    public DefaultEventProcessor(KafkaProducerService publisher, ObjectMapper objectMapper) {
        super(publisher, objectMapper);
    }

    @Override
    public String eventCode() {
        return "*";
    }

    @Override
    protected JsonNode processInternal(EventHeader incomingHeader, JsonNode payload, String messageId) {
        log.info("Processing message messageId={}", messageId);
        return payload;
    }
}
