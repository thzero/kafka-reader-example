package com.example.kafkaprocessor.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Envelope body carried in every incoming Kafka message.
 *
 * <p>{@code messageId} must be a canonical UUID string
 * (format: {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}).
 * Messages with a missing or malformed {@code messageId} are routed to dead letter
 * with reason code {@link com.example.kafkaprocessor.deadletter.ReasonCode#INVALID_MESSAGE_ID}
 * before entering the processing pipeline.
 */
public record MessageBody(
        @JsonProperty("messageId") String messageId) {
}
