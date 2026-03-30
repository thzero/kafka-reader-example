package com.example.kafkaprocessor.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EventHeader(
        @JsonProperty("interactionId") String interactionId,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("backdated") Boolean backdated) {
}
