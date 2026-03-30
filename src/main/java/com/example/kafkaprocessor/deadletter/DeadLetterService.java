package com.example.kafkaprocessor.deadletter;

public interface DeadLetterService {

    void handle(String rawPayload, ReasonCode reasonCode, String messageId, String interactionId);
}
