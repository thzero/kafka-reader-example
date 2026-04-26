package com.example.kafkametrics.processor.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.kafkametrics.kafka.KafkaProducerService;
import com.example.kafkametrics.model.EventHeader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public abstract class IIFMetricsEventProcessor extends MetricsEventProcessor<IIFMetricsOutput> {

    private static final Logger log = LoggerFactory.getLogger(IIFMetricsEventProcessor.class);

    protected IIFMetricsEventProcessor(KafkaProducerService publisher, ObjectMapper objectMapper) {
        super(publisher, objectMapper);
    }

    @Override
    protected IIFMetricsOutput processInternal(EventHeader incomingHeader, JsonNode payload, String messageId) {
        log.info("Processing IIF metrics message messageId={}", messageId);
        String interactionId = incomingHeader.interactionId();
        String eventType = incomingHeader.eventType();
        return new IIFMetricsOutput(messageId, interactionId, eventType);
    }
}
