package com.example.kafkaprocessor.kafka;

import com.example.kafkaprocessor.control.ControlService;
import com.example.kafkaprocessor.deadletter.DeadLetterService;
import com.example.kafkaprocessor.deadletter.ReasonCode;
import com.example.kafkaprocessor.logging.MdcContext;
import com.example.kafkaprocessor.model.KafkaMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaConsumerListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerListener.class);

    private final ObjectMapper objectMapper;
    private final ControlService controlService;
    private final MessageProcessorService messageProcessorService;
    private final KafkaProducerService kafkaProducerService;
    private final DeadLetterService deadLetterService;
    private final ScheduledExecutorService processingScheduler;

    @Value("${app.processing.delay-ms:20000}")
    private long processingDelayMs;

    public KafkaConsumerListener(ObjectMapper objectMapper,
                                 ControlService controlService,
                                 MessageProcessorService messageProcessorService,
                                 KafkaProducerService kafkaProducerService,
                                 DeadLetterService deadLetterService,
                                 ScheduledExecutorService processingScheduler) {
        this.objectMapper = objectMapper;
        this.controlService = controlService;
        this.messageProcessorService = messageProcessorService;
        this.kafkaProducerService = kafkaProducerService;
        this.deadLetterService = deadLetterService;
        this.processingScheduler = processingScheduler;
    }

    @KafkaListener(topics = "${kafka.topic.input}", containerFactory = "kafkaListenerContainerFactory")
    public void listen(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String rawPayload = record.value();

        // --- Deserialize ---
        KafkaMessage message;
        try {
            message = objectMapper.readValue(rawPayload, KafkaMessage.class);
        } catch (JsonProcessingException e) {
            log.error("Deserialization failed", e);
            deadLetterService.handle(rawPayload, ReasonCode.DESERIALIZATION_ERROR, null, null);
            return; // no ack — partition will stall until restarted with DLQ skip logic
        }

        String interactionId = message.event() != null ? message.event().interactionId() : null;
        String messageId = message.body() != null ? message.body().messageId() : null;

        MdcContext.set(interactionId, messageId);
        try {
            log.info("Message received");

            // --- Duplicate check via unique constraint ---
            // Attempt to INSERT the ReceivedRecord. If messageId already exists, the database
            // unique constraint fires a DataIntegrityViolationException — no separate SELECT needed.
            // This atomically closes the duplicate window, including messages still in-flight
            // waiting out their delay. To replay a failed message, delete its ReceivedRecord first.
            try {
                controlService.recordReceived(messageId, interactionId);
            } catch (DataIntegrityViolationException e) {
                log.warn("Duplicate messageId detected via unique constraint, routing to dead letter");
                deadLetterService.handle(rawPayload, ReasonCode.DUPLICATE, messageId, interactionId);
                acknowledgment.acknowledge();
                return;
            } catch (Exception e) {
                log.error("Failed to write RECEIVED control record", e);
                deadLetterService.handle(rawPayload, ReasonCode.CONTROL_RECORD_ERROR, messageId, interactionId);
                return; // no ack
            }

            // Capture MDC context so the scheduled worker thread can restore it.
            // MDC is thread-local — the worker runs on a different thread.
            Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

            // Schedule the remaining work (process → publish → write PUBLISHED → ack) after the
            // configured delay. The consumer thread returns immediately, freeing it to pull the
            // next message. Multiple messages can be in-flight simultaneously, each with their
            // own independent countdown.
            processingScheduler.schedule(
                () -> processDeferred(rawPayload, message, messageId, interactionId, acknowledgment, mdcSnapshot),
                processingDelayMs,
                TimeUnit.MILLISECONDS
            );

            log.info("Message accepted and scheduled for processing in {}ms", processingDelayMs);

        } finally {
            MdcContext.clear();
        }
    }

    private void processDeferred(String rawPayload, KafkaMessage message, String messageId,
                                  String interactionId, Acknowledgment acknowledgment,
                                  Map<String, String> mdcSnapshot) {
        // Restore MDC on this worker thread so all log entries carry the same correlation IDs
        if (mdcSnapshot != null) {
            MDC.setContextMap(mdcSnapshot);
        } else {
            MdcContext.set(interactionId, messageId);
        }
        try {
            // --- Process ---
            KafkaMessage processed;
            try {
                processed = messageProcessorService.process(message);
            } catch (Exception e) {
                log.error("Processing failed", e);
                deadLetterService.handle(rawPayload, ReasonCode.PROCESSING_ERROR, messageId, interactionId);
                return; // no ack
            }

            // --- Publish ---
            try {
                kafkaProducerService.publish(processed);
            } catch (Exception e) {
                log.error("Publish failed", e);
                deadLetterService.handle(rawPayload, ReasonCode.PUBLISH_ERROR, messageId, interactionId);
                return; // no ack
            }

            // --- Write PUBLISHED control record ---
            try {
                controlService.recordPublished(messageId, interactionId);
            } catch (Exception e) {
                log.error("Failed to write PUBLISHED control record", e);
                deadLetterService.handle(rawPayload, ReasonCode.CONTROL_RECORD_ERROR, messageId, interactionId);
                return; // no ack
            }

            // --- Acknowledge only on full success ---
            acknowledgment.acknowledge();

        } finally {
            MdcContext.clear();
        }
    }
}
