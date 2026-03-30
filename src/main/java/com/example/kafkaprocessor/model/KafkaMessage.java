package com.example.kafkaprocessor.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KafkaMessage(
        @JsonProperty("event") EventHeader event,
        @JsonProperty("body") MessageBody body) {
}
