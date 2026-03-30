package com.example.kafkaprocessor.deadletter;

public enum ReasonCode {
    DESERIALIZATION_ERROR,
    PROCESSING_ERROR,
    PUBLISH_ERROR,
    CONTROL_RECORD_ERROR,
    DUPLICATE
}
