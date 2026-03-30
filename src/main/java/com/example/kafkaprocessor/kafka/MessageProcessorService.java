package com.example.kafkaprocessor.kafka;

import com.example.kafkaprocessor.model.KafkaMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MessageProcessorService {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessorService.class);

    public KafkaMessage process(KafkaMessage message) {
        log.info("Processing started");
        // Business logic stub — return message as-is; replace with real transformation.
        // The 20-second delay is no longer here — it is handled upstream by the
        // ScheduledExecutorService in KafkaConsumerListener, which defers the call
        // to this method by the configured app.processing.delay-ms without blocking
        // the consumer thread.
        return message;
    }
}
