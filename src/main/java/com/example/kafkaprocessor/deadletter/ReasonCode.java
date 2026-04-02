package com.example.kafkaprocessor.deadletter;

public enum ReasonCode {
    INVALID_MESSAGE_ID,
    DESERIALIZATION_ERROR,
    LISTENING_ERROR,
    PROCESSING_ERROR,
    PUBLISH_ERROR,
    CONTROL_RECORD_ERROR,
    DUPLICATE
}
