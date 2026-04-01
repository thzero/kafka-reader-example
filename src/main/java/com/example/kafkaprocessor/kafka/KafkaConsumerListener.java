package com.example.kafkaprocessor.kafka;

import com.example.kafkaprocessor.control.ControlService;
import com.example.kafkaprocessor.deadletter.DeadLetterService;
import com.example.kafkaprocessor.deadletter.ReasonCode;
import com.example.kafkaprocessor.logging.MdcContext;
import com.example.kafkaprocessor.model.KafkaMessage;
import com.example.kafkaprocessor.kafka.siphon.SiphonEvaluator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class KafkaConsumerListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerListener.class);

    private final ObjectMapper objectMapper;
    private final ControlService controlService;
    private final MessageProcessorService messageProcessorService;
    private final KafkaProducerService kafkaProducerService;
    private final DeadLetterService deadLetterService;
    private final List<SiphonEvaluator> siphonEvaluators;
    private final MeterRegistry meterRegistry;

    // Pre-cached Micrometer meters keyed by eventType.
    // Avoids tag-array (String[]) allocation and registry map lookup on every message in the hot path.
    // computeIfAbsent registers the meter on first use per eventType; all subsequent calls are a single CHM get.
    private final ConcurrentHashMap<String, Counter> receivedCounters  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> publishedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> siphonedCounters  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer>   pipelineTimers    = new ConcurrentHashMap<>();

    @Value("${kafka.topic.output}")
    private String outputTopic;

    public KafkaConsumerListener(ObjectMapper objectMapper,
                                 ControlService controlService,
                                 MessageProcessorService messageProcessorService,
                                 KafkaProducerService kafkaProducerService,
                                 DeadLetterService deadLetterService,
                                 @Qualifier("activeSiphonEvaluators") List<SiphonEvaluator> siphonEvaluators,
                                 MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.controlService = controlService;
        this.messageProcessorService = messageProcessorService;
        this.kafkaProducerService = kafkaProducerService;
        this.deadLetterService = deadLetterService;
        this.siphonEvaluators = siphonEvaluators;
        this.meterRegistry = meterRegistry;
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
            meterRegistry.counter("kafka.processor.messages.failed", "reason", "DESERIALIZATION_ERROR").increment();
            deadLetterService.handle(rawPayload, ReasonCode.DESERIALIZATION_ERROR, null, null);
            return; // no ack — partition will stall until restarted with DLQ skip logic
        }

        String interactionId = message.event() != null ? message.event().interactionId() : null;
        String messageId = message.body() != null ? message.body().messageId() : null;
        String eventType = (message.event() != null && message.event().eventType() != null)
                ? message.event().eventType() : "unknown";

        // --- Validate messageId is a well-formed UUID ---
        if (messageId == null || !isValidUuid(messageId)) {
            log.error("Invalid or missing messageId, routing to dead letter: messageId={}", messageId);
            meterRegistry.counter("kafka.processor.messages.failed", "reason", "INVALID_MESSAGE_ID").increment();
            deadLetterService.handle(rawPayload, ReasonCode.INVALID_MESSAGE_ID, messageId, interactionId);
            return; // no ack — partition will stall until restarted with DLQ skip logic
        }

        MdcContext.set(interactionId, messageId);
        try {
            log.info("[RECEIVED] eventType={} topic={} partition={} offset={}",
                    eventType, record.topic(), record.partition(), record.offset());

            // --- Siphon fast-path (before any other processing) ---
            // Each SiphonEvaluator returns a topic name or empty. First match wins.
            // To add a route: implement SiphonEvaluator, register as @Component, add code to app.siphon.enabled.
            Optional<String> siphonTopic = siphonEvaluators.isEmpty() ? Optional.empty() :
                    siphonEvaluators.stream()
                            .map(e -> e.evaluate(message))
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .findFirst();
            if (siphonTopic.isPresent()) {
                log.info("[SIPHON] eventType={} -> topic={}", eventType, siphonTopic.get());
                try {
                    kafkaProducerService.publish(messageId, rawPayload, siphonTopic.get());
                } catch (Exception e) {
                    log.error("[SIPHON-FAIL] eventType={} topic={} -- publish failed", eventType, siphonTopic.get(), e);
                    return; // no ack — redelivery
                }
                siphonedCounters.computeIfAbsent(eventType, et -> meterRegistry.counter("kafka.processor.messages.siphoned", "eventType", et)).increment();
                acknowledgment.acknowledge();
                log.info("[SIPHON-ACK] eventType={} topic={}", eventType, siphonTopic.get());
                return;
            }

            receivedCounters.computeIfAbsent(eventType, et -> meterRegistry.counter("kafka.processor.messages.received", "eventType", et)).increment();
            Timer.Sample pipelineSample = Timer.start(meterRegistry);
            try {
                // --- Write RECEIVED control record ---
                // DataIntegrityViolationException means the app restarted mid-flight — the ReceivedRecord
                // row survived but our in-memory state did not. Treat as replay duplicate: DLQ + ack.
                log.info("[PROCESSING] eventType={}", eventType);
                try {
                    controlService.recordReceived(messageId, interactionId);
                } catch (DataIntegrityViolationException e) {
                    log.warn("[DUPLICATE] eventType={} -- restart/replay duplicate detected, routing to dead letter", eventType);
                    meterRegistry.counter("kafka.processor.messages.failed", "reason", "DUPLICATE").increment();
                    deadLetterService.handle(rawPayload, ReasonCode.DUPLICATE, messageId, interactionId);
                    acknowledgment.acknowledge();
                    return;
                } catch (Exception e) {
                    log.error("Failed to write RECEIVED control record", e);
                    meterRegistry.counter("kafka.processor.messages.failed", "reason", "CONTROL_RECORD_ERROR").increment();
                    deadLetterService.handle(rawPayload, ReasonCode.CONTROL_RECORD_ERROR, messageId, interactionId);
                    return; // no ack
                }

                // --- Process and Publish (delegated to EventProcessor via MessageProcessorService) ---
                // The consumer thread blocks here until processing completes, then moves to the next message.
                // Timeout is enforced by delivery.timeout.ms on the Kafka producer.
                try {
                    messageProcessorService.process(message);
                } catch (KafkaPublishException e) {
                    log.error("Publish failed", e);
                    meterRegistry.counter("kafka.processor.messages.failed", "reason", "PUBLISH_ERROR").increment();
                    deadLetterService.handle(rawPayload, ReasonCode.PUBLISH_ERROR, messageId, interactionId);
                    return; // no ack
                } catch (Exception e) {
                    log.error("Processing failed", e);
                    meterRegistry.counter("kafka.processor.messages.failed", "reason", "PROCESSING_ERROR").increment();
                    deadLetterService.handle(rawPayload, ReasonCode.PROCESSING_ERROR, messageId, interactionId);
                    return; // no ack
                }

                // --- Write PUBLISHED control record ---
                try {
                    controlService.recordPublished(messageId, interactionId);
                } catch (Exception e) {
                    log.error("Failed to write PUBLISHED control record", e);
                    meterRegistry.counter("kafka.processor.messages.failed", "reason", "CONTROL_RECORD_ERROR").increment();
                    deadLetterService.handle(rawPayload, ReasonCode.CONTROL_RECORD_ERROR, messageId, interactionId);
                    return; // no ack
                }

                // --- Acknowledge only on full success ---
                publishedCounters.computeIfAbsent(eventType, et -> meterRegistry.counter("kafka.processor.messages.published", "eventType", et)).increment();
                acknowledgment.acknowledge();
                log.info("[PUBLISHED] eventType={} -> topic={}", eventType, outputTopic);

            } finally {
                pipelineSample.stop(pipelineTimers.computeIfAbsent(eventType, et -> meterRegistry.timer("kafka.processor.pipeline.latency", "eventType", et)));
            }
        } catch (Exception e) {
            deadLetterService.handle(rawPayload, ReasonCode.LISTENING_ERROR, messageId, interactionId);
        } finally {
            MdcContext.clear();
        }
    }

    private static boolean isValidUuid(String value) {
        if (value == null || value.length() != 36) return false;
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
