package com.example.kafkaprocessor.kafka;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.kafkaprocessor.control.ControlService;
import com.example.kafkaprocessor.deadletter.DeadLetterService;
import com.example.kafkaprocessor.deadletter.ReasonCode;
import com.example.kafkaprocessor.kafka.siphon.SiphonEvaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
class KafkaConsumerListenerTest {

    @Mock private ControlService controlService;
    @Mock private MessageProcessorService messageProcessorService;
    @Mock private KafkaProducerService kafkaProducerService;
    @Mock private DeadLetterService deadLetterService;
    @Mock private SiphonEvaluator siphonEvaluator;
    @Mock private Acknowledgment acknowledgment;

    private KafkaConsumerListener listener;
    private ObjectMapper objectMapper;

    // A real single-thread scheduler is used so scheduled tasks actually execute.
    // delay-ms is set to 0 via ReflectionTestUtils so tests run synchronously.
    private ScheduledExecutorService scheduler;

    private static final String MSG_ID_1   = "00000000-0000-0000-0000-000000000001";
    private static final String IID_1 = "iid-1";

    private static final String VALID_PAYLOAD =
            "{\"event\":{\"interactionId\":\"iid-1\",\"eventType\":\"TEST\"},\"body\":{\"messageId\":\"00000000-0000-0000-0000-000000000001\"}}";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        var created = new KafkaConsumerListener(
                objectMapper, controlService,
                messageProcessorService, kafkaProducerService, deadLetterService, scheduler,
                List.of(siphonEvaluator), new SimpleMeterRegistry());
        // Zero delay so deferred work fires immediately, keeping tests fast and deterministic
        ReflectionTestUtils.setField(created, "processorDelayMs", 0L);
        ReflectionTestUtils.setField(created, "processorLoadDelayMs", 0L);
        listener = created;
    }

    private ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("input-topic", 0, 0L, null, payload);
    }

    // Waits for the scheduler to finish all currently queued tasks
    private void awaitScheduler() throws InterruptedException {
        scheduler.schedule(() -> {}, 0, TimeUnit.MILLISECONDS);
        scheduler.shutdown();
        scheduler.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void happyPath_writesControlRecords_publishesAndAcks() throws InterruptedException {
        // messageProcessorService.process() is void — default mock behaviour (do nothing) is correct.
        // The actual publish is done inside the EventProcessor, not in the listener.

        listener.listen(record(VALID_PAYLOAD), acknowledgment);
        awaitScheduler();

        verify(controlService).recordReceived(MSG_ID_1, IID_1);
        verify(messageProcessorService).process(any());
        verify(controlService).recordPublished(MSG_ID_1, IID_1);
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(deadLetterService);
        // publish is now inside EventProcessor — not verified on kafkaProducerService here
    }

    @Test
    void invalidMessageId_routesToDeadLetter_noAck() {
        String badPayload = "{\"event\":{\"interactionId\":\"iid-1\",\"eventType\":\"TEST\"},\"body\":{\"messageId\":\"not-a-uuid\"}}";
        listener.listen(record(badPayload), acknowledgment);

        verify(deadLetterService).handle(eq(badPayload), eq(ReasonCode.INVALID_MESSAGE_ID), eq("not-a-uuid"), eq(IID_1));
        verify(acknowledgment, never()).acknowledge();
        verifyNoInteractions(controlService, messageProcessorService, kafkaProducerService);
    }

    @Test
    void nullMessageId_routesToDeadLetter_noAck() {
        String noBodyPayload = "{\"event\":{\"interactionId\":\"iid-1\",\"eventType\":\"TEST\"}}";
        listener.listen(record(noBodyPayload), acknowledgment);

        verify(deadLetterService).handle(eq(noBodyPayload), eq(ReasonCode.INVALID_MESSAGE_ID), isNull(), eq(IID_1));
        verify(acknowledgment, never()).acknowledge();
        verifyNoInteractions(controlService, messageProcessorService, kafkaProducerService);
    }

    @Test
    void deserializationFailure_routesToDeadLetter_noAck() throws InterruptedException {
        listener.listen(record("not-valid-json"), acknowledgment);
        awaitScheduler();

        verify(deadLetterService).handle(eq("not-valid-json"), eq(ReasonCode.DESERIALIZATION_ERROR), isNull(), isNull());
        verify(acknowledgment, never()).acknowledge();
        verifyNoInteractions(controlService, messageProcessorService, kafkaProducerService);
    }

    @Test
    void bdeEvent_siphonsToSiphonTopic_acks() {
        String bdePayload = "{\"event\":{\"interactionId\":\"iid-2\",\"eventType\":\"END\",\"backdated\":true},\"body\":{\"messageId\":\"00000000-0000-0000-0000-0000000000bd\"}}";
        when(siphonEvaluator.evaluate(any())).thenReturn(Optional.of("test-siphon-topic"));

        listener.listen(record(bdePayload), acknowledgment);

        verify(kafkaProducerService).publish(eq("00000000-0000-0000-0000-0000000000bd"), eq(bdePayload), eq("test-siphon-topic"));
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(controlService, messageProcessorService, deadLetterService);
    }

    @Test
    void bdeEvent_siphonFailure_noAck() {
        String bdePayload = "{\"event\":{\"interactionId\":\"iid-2\",\"eventType\":\"END\",\"backdated\":true},\"body\":{\"messageId\":\"00000000-0000-0000-0000-0000000000bd\"}}";
        when(siphonEvaluator.evaluate(any())).thenReturn(Optional.of("test-siphon-topic"));
        doThrow(new KafkaPublishException("siphon failed", new RuntimeException()))
                .when(kafkaProducerService).publish(any(), anyString(), any());

        listener.listen(record(bdePayload), acknowledgment);

        verify(acknowledgment, never()).acknowledge();
        verifyNoInteractions(controlService, messageProcessorService, deadLetterService);
    }

    @Test
    void duplicateMessage_inFlight_routesToDeadLetter_acks() {
        // Use a mock scheduler so the first message's worker task is captured but never executed,
        // keeping its messageId in the in-flight set when the second message arrives.
        ScheduledExecutorService nonExecutingScheduler = mock(ScheduledExecutorService.class);
        var l = new KafkaConsumerListener(
                objectMapper, controlService, messageProcessorService,
                kafkaProducerService, deadLetterService, nonExecutingScheduler,
                List.of(siphonEvaluator), new SimpleMeterRegistry());
        ReflectionTestUtils.setField(l, "processorDelayMs", 0L);
        ReflectionTestUtils.setField(l, "processorLoadDelayMs", 0L);

        Acknowledgment ack2 = mock(Acknowledgment.class);

        // First arrival — accepted, scheduled (task never executes — mock scheduler)
        l.listen(record(VALID_PAYLOAD), acknowledgment);
        // Second arrival with same messageId — hits in-flight duplicate gate
        l.listen(record(VALID_PAYLOAD), ack2);

        verify(deadLetterService).handle(eq(VALID_PAYLOAD), eq(ReasonCode.DUPLICATE), eq(MSG_ID_1), eq(IID_1));
        verify(ack2).acknowledge();
        verify(acknowledgment, never()).acknowledge();
        verifyNoInteractions(messageProcessorService, kafkaProducerService, controlService);
    }

    @Test
    void processingFailure_routesToDeadLetter_noAck() throws InterruptedException {
        doThrow(new ProcessingException("boom")).when(messageProcessorService).process(any());

        listener.listen(record(VALID_PAYLOAD), acknowledgment);
        awaitScheduler();

        verify(deadLetterService).handle(eq(VALID_PAYLOAD), eq(ReasonCode.PROCESSING_ERROR), eq(MSG_ID_1), eq(IID_1));
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void publishFailure_routesToDeadLetter_noAck() throws InterruptedException {
        doThrow(new KafkaPublishException("publish failed", new RuntimeException()))
                .when(messageProcessorService).process(any());

        listener.listen(record(VALID_PAYLOAD), acknowledgment);
        awaitScheduler();

        verify(deadLetterService).handle(eq(VALID_PAYLOAD), eq(ReasonCode.PUBLISH_ERROR), eq(MSG_ID_1), eq(IID_1));
        verify(acknowledgment, never()).acknowledge();
        verify(controlService, never()).recordPublished(any(), any());
    }
}
