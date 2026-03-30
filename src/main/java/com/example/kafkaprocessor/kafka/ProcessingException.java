package com.example.kafkaprocessor.kafka;

public class ProcessingException extends RuntimeException {

    public ProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProcessingException(String message) {
        super(message);
    }
}
