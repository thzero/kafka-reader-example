package com.example.kafkametrics.processor.metrics;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Output payload produced by {@link IIFMetricsEventProcessor}.
 */
public record IIFMetricsOutput(
        @JsonProperty("messageId") String messageId,
        @JsonProperty("interactionId") String interactionId,
        @JsonProperty("eventType") String eventType) {
}
