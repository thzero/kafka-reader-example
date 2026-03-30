package com.example.kafkaprocessor.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MessageBody(
        @JsonProperty("messageId") String messageId) {
}
