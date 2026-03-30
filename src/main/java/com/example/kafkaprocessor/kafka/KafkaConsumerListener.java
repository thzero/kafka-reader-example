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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    // In-memory set of messageIds currently in-flight (consumer thread → worker thread).
    // add() returns false if already present → duplicate detected in nanoseconds with no DB call.
    // Cleared in the worker's finally block so redelivered messages can re-enter the pipeline.
    private final Set<String> inFlightIds = ConcurrentHashMap.newKeySet();

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

            // --- In-memory duplicate gate (nanosecond cost, no DB round-trip) ---
            // ConcurrentHashMap.add() returns false if the messageId was already present, meaning
            // this messageId is still in-flight (consumer delivered a duplicate within the delay window).
            // This is purely in-memory; it is cleared on restart. Restart/replay duplicates are caught
            // by the DB unique constraint on ReceivedRecord.message_id inside the worker thread.
            if (messageId != null && !inFlightIds.add(messageId)) {
                log.warn("Duplicate messageId detected in-flight, routing to dead letter");
                deadLetterService.handle(rawPayload, ReasonCode.DUPLICATE, messageId, interactionId);
                acknowledgment.acknowledge();
                return;
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
            // --- Write RECEIVED control record ---
            // Done on the worker thread so the DB round-trip (Oracle/SQL Server) does not block
            // the consumer thread. DataIntegrityViolationException here means the app restarted
            // mid-flight — the in-memory set was lost but the ReceivedRecord row survived.
            // Treat as a restart/replay duplicate: route to dead letter and ack.
            try {
                controlService.recordReceived(messageId, interactionId);
            } catch (DataIntegrityViolationException e) {
                log.warn("ReceivedRecord already exists (restart/replay duplicate), routing to dead letter");
                deadLetterService.handle(rawPayload, ReasonCode.DUPLICATE, messageId, interactionId);
                acknowledgment.acknowledge();
                return;
            } catch (Exception e) {
                log.error("Failed to write RECEIVED control record", e);
                deadLetterService.handle(rawPayload, ReasonCode.CONTROL_RECORD_ERROR, messageId, interactionId);
                return; // no ack
            }

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
            // Always release the in-flight slot:
            // - On success: clears the slot after ack so an explicit replay can re-enter.
            // - On failure (no-ack): frees the slot so the redelivered message can re-enter.
            if (messageId != null) {
                inFlightIds.remove(messageId);
            }
            MdcContext.clear();
        }
    }
}
