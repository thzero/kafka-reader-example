package com.example.kafkaprocessor.kafka;

import com.example.kafkaprocessor.control.ControlService;
import com.example.kafkaprocessor.deadletter.DeadLetterService;
import com.example.kafkaprocessor.deadletter.ReasonCode;
import com.example.kafkaprocessor.kafka.siphon.SiphonEvaluator;
import com.example.kafkaprocessor.logging.MdcContext;
import com.example.kafkaprocessor.model.KafkaMessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final List<SiphonEvaluator> siphonEvaluators;
    private final MeterRegistry meterRegistry;

    // In-memory set of messageIds currently in-flight (consumer thread → worker thread).
    // add() returns false if already present → duplicate detected in nanoseconds with no DB call.
    // Cleared in the worker's finally block so redelivered messages can re-enter the pipeline.
    private final Set<String> inFlightIds = ConcurrentHashMap.newKeySet();

    // Pre-cached Micrometer meters keyed by eventType.
    // Avoids tag-array (String[]) allocation and registry map lookup on every message in the hot path.
    // computeIfAbsent registers the meter on first use per eventType; all subsequent calls are O(1) CHM get.
    private final ConcurrentHashMap<String, Counter> receivedCounters  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> publishedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> siphonedCounters  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer>   e2eTimers         = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer>   pipelineTimers    = new ConcurrentHashMap<>();

    @Value("${app.processing.processor-delay-ms:20000}")
    private long processorDelayMs;
    @Value("${app.processing.processor-load-delay-ms:0}")
    private long processorLoadDelayMs;

    @Value("${app.processing.status-log-interval-ms:10000}")
    private long statusLogIntervalMs;

    @Value("${kafka.topic.output}")
    private String outputTopic;

    public KafkaConsumerListener(ObjectMapper objectMapper,
                                 ControlService controlService,
                                 MessageProcessorService messageProcessorService,
                                 KafkaProducerService kafkaProducerService,
                                 DeadLetterService deadLetterService,
                                 @Qualifier("processingScheduler") ScheduledExecutorService processingScheduler,
                                 @Qualifier("activeSiphonEvaluators") List<SiphonEvaluator> siphonEvaluators,
                                 MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.controlService = controlService;
        this.messageProcessorService = messageProcessorService;
        this.kafkaProducerService = kafkaProducerService;
        this.deadLetterService = deadLetterService;
        this.processingScheduler = processingScheduler;
        this.siphonEvaluators = siphonEvaluators;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    private void startStatusLogger() {
        Gauge.builder("kafka.processor.messages.in.flight", inFlightIds, Set::size)
                .description("Number of messages currently in-flight (scheduled + executing)")
                .register(meterRegistry);
        if (statusLogIntervalMs > 0) {
            processingScheduler.scheduleAtFixedRate(this::logStatus, statusLogIntervalMs, statusLogIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    // -------------------------------------------------------------------------
    // Main listener
    // -------------------------------------------------------------------------

    @KafkaListener(topics = "${kafka.topic.input}", containerFactory = "kafkaListenerContainerFactory")
    public void listen(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        Optional<MessageContext> ctxOpt = deserializeAndValidate(record.value());
        if (ctxOpt.isEmpty()) return;

        MessageContext ctx = ctxOpt.get();
        MdcContext.set(ctx.interactionId(), ctx.messageId());
        try {
            log.info("[RECEIVED] eventType={} topic={} partition={} offset={}",
                    ctx.eventType(), record.topic(), record.partition(), record.offset());

            if (trySiphon(ctx, acknowledgment)) return;

            // In-memory duplicate gate — nanosecond cost, no DB round-trip.
            // Restart/replay duplicates are caught by the DB unique constraint inside processDeferred.
            if (!inFlightIds.add(ctx.messageId())) {
                log.warn("Duplicate messageId detected in-flight, routing to dead letter");
                meterRegistry.counter("kafka.processor.messages.failed", "reason", "DUPLICATE").increment();
                deadLetterService.handle(ctx.rawPayload(), ReasonCode.DUPLICATE, ctx.messageId(), ctx.interactionId());
                acknowledgment.acknowledge();
                return;
            }

            // MDC snapshot so the worker thread can restore correlation IDs (MDC is thread-local).
            Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
            // e2e timer starts here — after all fast-path exits — to capture delay + execution time.
            Timer.Sample e2eSample = Timer.start(meterRegistry);
            counter(receivedCounters, ctx.eventType(), "kafka.processor.messages.received").increment();

            try {
                // Task sits in the delay queue — NO thread consumed while waiting.
                processingScheduler.schedule(
                        () -> processDeferred(ctx, acknowledgment, e2eSample, mdcSnapshot),
                        processorDelayMs + processorLoadDelayMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // RejectedExecutionException (pool shutting down) — clean up before returning so
                // the inFlightIds slot does not leak (processDeferred will never run to clear it).
                log.error("[SCHEDULE-FAIL] eventType={} -- failed to schedule, routing to dead letter", ctx.eventType(), e);
                inFlightIds.remove(ctx.messageId());
                e2eSample.stop(timer(e2eTimers, ctx.eventType(), "kafka.processor.e2e.latency"));
                meterRegistry.counter("kafka.processor.messages.failed", "reason", "PROCESSING_ERROR").increment();
                deadLetterService.handle(ctx.rawPayload(), ReasonCode.PROCESSING_ERROR, ctx.messageId(), ctx.interactionId());
                return;
            }
            log.info("[SCHEDULED] eventType={} inFlight={}", ctx.eventType(), inFlightIds.size());
        } catch (Exception e) {
            deadLetterService.handle(ctx.rawPayload(), ReasonCode.LISTENING_ERROR, ctx.messageId(), ctx.interactionId());
        } finally {
            MdcContext.clear();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers called from listen()
    // -------------------------------------------------------------------------

    /** Deserializes the payload and validates the messageId UUID. Returns empty and routes to DLQ on any failure. */
    private Optional<MessageContext> deserializeAndValidate(String rawPayload) {
        KafkaMessage message;
        try {
            message = objectMapper.readValue(rawPayload, KafkaMessage.class);
        } catch (JsonProcessingException e) {
            log.error("Deserialization failed", e);
            meterRegistry.counter("kafka.processor.messages.failed", "reason", "DESERIALIZATION_ERROR").increment();
            deadLetterService.handle(rawPayload, ReasonCode.DESERIALIZATION_ERROR, null, null);
            return Optional.empty();
        }

        String interactionId = message.event() != null ? message.event().interactionId() : null;
        String messageId     = message.body()  != null ? message.body().messageId()       : null;
        String eventType     = (message.event() != null && message.event().eventType() != null)
                ? message.event().eventType() : "unknown";

        if (messageId == null || !isValidUuid(messageId)) {
            log.error("Invalid or missing messageId, routing to dead letter: messageId={}", messageId);
            meterRegistry.counter("kafka.processor.messages.failed", "reason", "INVALID_MESSAGE_ID").increment();
            deadLetterService.handle(rawPayload, ReasonCode.INVALID_MESSAGE_ID, messageId, interactionId);
            return Optional.empty();
        }

        return Optional.of(new MessageContext(rawPayload, message, messageId, interactionId, eventType));
    }

    /**
     * Checks whether the message matches a siphon route. If so, publishes to the siphon topic and acks.
     *
     * @return {@code true} if the message was handled (caller should return), {@code false} to continue the pipeline.
     */
    private boolean trySiphon(MessageContext ctx, Acknowledgment acknowledgment) {
        Optional<String> siphonTopic = siphonEvaluators.stream()
                .map(e -> e.evaluate(ctx.message()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
        if (siphonTopic.isEmpty()) return false;

        log.info("[SIPHON] eventType={} -> topic={}", ctx.eventType(), siphonTopic.get());
        try {
            kafkaProducerService.publish(ctx.messageId(), ctx.rawPayload(), siphonTopic.get());
        } catch (Exception e) {
            log.error("[SIPHON-FAIL] eventType={} topic={} -- publish failed", ctx.eventType(), siphonTopic.get(), e);
            return true; // no ack — redelivery
        }
        counter(siphonedCounters, ctx.eventType(), "kafka.processor.messages.siphoned").increment();
        acknowledgment.acknowledge();
        log.info("[SIPHON-ACK] eventType={} topic={}", ctx.eventType(), siphonTopic.get());
        return true;
    }

    // -------------------------------------------------------------------------
    // Deferred worker (runs on scheduler thread after processorDelayMs)
    // -------------------------------------------------------------------------

    private void processDeferred(MessageContext ctx, Acknowledgment acknowledgment,
                                 Timer.Sample e2eSample, Map<String, String> mdcSnapshot) {
        if (mdcSnapshot != null) {
            MDC.setContextMap(mdcSnapshot);
        } else {
            MdcContext.set(ctx.interactionId(), ctx.messageId());
        }
        Timer.Sample pipelineSample = Timer.start(meterRegistry);
        try {
            log.info("[PROCESSING] eventType={}", ctx.eventType());

            try {
                controlService.recordReceived(ctx.messageId(), ctx.interactionId());
            } catch (DataIntegrityViolationException e) {
                // Row already exists — app restarted mid-flight; in-memory state was lost but DB row survived.
                log.warn("[DUPLICATE] eventType={} -- restart/replay duplicate, routing to dead letter", ctx.eventType());
                meterRegistry.counter("kafka.processor.messages.failed", "reason", "DUPLICATE").increment();
                deadLetterService.handle(ctx.rawPayload(), ReasonCode.DUPLICATE, ctx.messageId(), ctx.interactionId());
                acknowledgment.acknowledge();
                return;
            } catch (Exception e) {
                log.error("Failed to write RECEIVED control record", e);
                meterRegistry.counter("kafka.processor.messages.failed", "reason", "CONTROL_RECORD_ERROR").increment();
                deadLetterService.handle(ctx.rawPayload(), ReasonCode.CONTROL_RECORD_ERROR, ctx.messageId(), ctx.interactionId());
                return;
            }

            // Runs on a platform thread — no VT wrapping. VTs pin on Kafka's synchronized blocks,
            // exhausting the ForkJoinPool. Timeout enforced by delivery.timeout.ms on the producer.
            try {
                messageProcessorService.process(ctx.message());
            } catch (KafkaPublishException e) {
                log.error("Publish failed", e);
                meterRegistry.counter("kafka.processor.messages.failed", "reason", "PUBLISH_ERROR").increment();
                deadLetterService.handle(ctx.rawPayload(), ReasonCode.PUBLISH_ERROR, ctx.messageId(), ctx.interactionId());
                return;
            } catch (Exception e) {
                log.error("Processing failed", e);
                meterRegistry.counter("kafka.processor.messages.failed", "reason", "PROCESSING_ERROR").increment();
                deadLetterService.handle(ctx.rawPayload(), ReasonCode.PROCESSING_ERROR, ctx.messageId(), ctx.interactionId());
                return;
            }

            try {
                controlService.recordPublished(ctx.messageId(), ctx.interactionId());
            } catch (Exception e) {
                log.error("Failed to write PUBLISHED control record", e);
                meterRegistry.counter("kafka.processor.messages.failed", "reason", "CONTROL_RECORD_ERROR").increment();
                deadLetterService.handle(ctx.rawPayload(), ReasonCode.CONTROL_RECORD_ERROR, ctx.messageId(), ctx.interactionId());
                return;
            }

            counter(publishedCounters, ctx.eventType(), "kafka.processor.messages.published").increment();
            acknowledgment.acknowledge();
            log.info("[PUBLISHED] eventType={} -> topic={}", ctx.eventType(), outputTopic);

        } finally {
            pipelineSample.stop(timer(pipelineTimers, ctx.eventType(), "kafka.processor.pipeline.latency"));
            e2eSample.stop(timer(e2eTimers, ctx.eventType(), "kafka.processor.e2e.latency"));
            inFlightIds.remove(ctx.messageId()); // always release — success clears for replay, failure clears for redelivery
            MDC.clear(); // full clear — pool threads are reused; selective clear risks leaking keys
        }
    }

    private void logStatus() {
        int inFlight = inFlightIds.size();
        if (inFlight > 0) {
            log.info("[STATUS] inFlight={}", inFlight);
        } else {
            log.debug("[STATUS] idle — inFlight=0");
        }
    }

    // -------------------------------------------------------------------------
    // Meter cache helpers — avoid per-call String[] allocation + registry lookup
    // -------------------------------------------------------------------------

    private Counter counter(ConcurrentHashMap<String, Counter> cache, String eventType, String name) {
        return cache.computeIfAbsent(eventType, et -> meterRegistry.counter(name, "eventType", et));
    }

    private Timer timer(ConcurrentHashMap<String, Timer> cache, String eventType, String name) {
        return cache.computeIfAbsent(eventType, et -> meterRegistry.timer(name, "eventType", et));
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

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    // Bundles per-message data so it can be passed as a unit rather than as individual parameters.
    private record MessageContext(
            String rawPayload, KafkaMessage message,
            String messageId, String interactionId, String eventType) {}
}
