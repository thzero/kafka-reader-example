package com.example.kafkaprocessor.kafka;

import com.example.kafkaprocessor.control.ControlService;
import com.example.kafkaprocessor.deadletter.DeadLetterService;
import com.example.kafkaprocessor.deadletter.ReasonCode;
import com.example.kafkaprocessor.model.EventHeader;
import com.example.kafkaprocessor.model.KafkaMessage;
import com.example.kafkaprocessor.model.MessageBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaConsumerListenerTest {

    @Mock private ControlService controlService;
    @Mock private MessageProcessorService messageProcessorService;
    @Mock private KafkaProducerService kafkaProducerService;
    @Mock private DeadLetterService deadLetterService;
    @Mock private Acknowledgment acknowledgment;

    private KafkaConsumerListener listener;
    private ObjectMapper objectMapper;

    // A real single-thread scheduler is used so scheduled tasks actually execute.
    // delay-ms is set to 0 via ReflectionTestUtils so tests run synchronously.
    private ScheduledExecutorService scheduler;

    private static final String VALID_PAYLOAD =
            "{\"event\":{\"interactionId\":\"iid-1\",\"eventType\":\"TEST\"},\"body\":{\"messageId\":\"msg-1\"}}";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        listener = new KafkaConsumerListener(
                objectMapper, controlService,
                messageProcessorService, kafkaProducerService, deadLetterService, scheduler);
        // Zero delay so deferred work fires immediately, keeping tests fast and deterministic
        ReflectionTestUtils.setField(listener, "processingDelayMs", 0L);
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
        KafkaMessage processed = new KafkaMessage(
                new EventHeader("iid-1", "TEST", null), new MessageBody("msg-1"));
        when(messageProcessorService.process(any())).thenReturn(processed);

        listener.listen(record(VALID_PAYLOAD), acknowledgment);
        awaitScheduler();

        verify(controlService).recordReceived("msg-1", "iid-1");
        verify(messageProcessorService).process(any());
        verify(kafkaProducerService).publish(processed);
        verify(controlService).recordPublished("msg-1", "iid-1");
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(deadLetterService);
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
    void bdeEvent_siphonsToSiphonTopic_acks() throws InterruptedException {
        String bdePayload = "{\"event\":{\"interactionId\":\"iid-2\",\"eventType\":\"END\",\"backdated\":true},\"body\":{\"messageId\":\"msg-bde\"}}";

        listener.listen(record(bdePayload), acknowledgment);

        verify(kafkaProducerService).siphon(any());
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(controlService, messageProcessorService, deadLetterService);
        verify(kafkaProducerService, never()).publish(any());
    }

    @Test
    void bdeEvent_siphonFailure_noAck() throws InterruptedException {
        String bdePayload = "{\"event\":{\"interactionId\":\"iid-2\",\"eventType\":\"END\",\"backdated\":true},\"body\":{\"messageId\":\"msg-bde\"}}";
        doThrow(new KafkaPublishException("siphon failed", new RuntimeException()))
                .when(kafkaProducerService).siphon(any());

        listener.listen(record(bdePayload), acknowledgment);

        verify(acknowledgment, never()).acknowledge();
        verifyNoInteractions(controlService, messageProcessorService, deadLetterService);
    }

    @Test
    void duplicateMessage_inFlight_routesToDeadLetter_acks() throws InterruptedException {
        // Use a mock scheduler so the first message's worker task is captured but never executed,
        // keeping its messageId in the in-flight set when the second message arrives.
        ScheduledExecutorService nonExecutingScheduler = mock(ScheduledExecutorService.class);
        KafkaConsumerListener l = new KafkaConsumerListener(
                objectMapper, controlService, messageProcessorService,
                kafkaProducerService, deadLetterService, nonExecutingScheduler);
        ReflectionTestUtils.setField(l, "processingDelayMs", 0L);

        Acknowledgment ack2 = mock(Acknowledgment.class);

        // First arrival — accepted, scheduled (task never executes — mock scheduler)
        l.listen(record(VALID_PAYLOAD), acknowledgment);
        // Second arrival with same messageId — hits in-flight duplicate gate
        l.listen(record(VALID_PAYLOAD), ack2);

        verify(deadLetterService).handle(eq(VALID_PAYLOAD), eq(ReasonCode.DUPLICATE), eq("msg-1"), eq("iid-1"));
        verify(ack2).acknowledge();
        verify(acknowledgment, never()).acknowledge();
        verifyNoInteractions(messageProcessorService, kafkaProducerService, controlService);
    }

    @Test
    void processingFailure_routesToDeadLetter_noAck() throws InterruptedException {
        when(messageProcessorService.process(any())).thenThrow(new ProcessingException("boom"));

        listener.listen(record(VALID_PAYLOAD), acknowledgment);
        awaitScheduler();

        verify(deadLetterService).handle(eq(VALID_PAYLOAD), eq(ReasonCode.PROCESSING_ERROR), eq("msg-1"), eq("iid-1"));
        verify(acknowledgment, never()).acknowledge();
        verify(kafkaProducerService, never()).publish(any());
    }

    @Test
    void publishFailure_routesToDeadLetter_noAck() throws InterruptedException {
        KafkaMessage processed = new KafkaMessage(
                new EventHeader("iid-1", "TEST", null), new MessageBody("msg-1"));
        when(messageProcessorService.process(any())).thenReturn(processed);
        doThrow(new KafkaPublishException("publish failed", new RuntimeException()))
                .when(kafkaProducerService).publish(any());

        listener.listen(record(VALID_PAYLOAD), acknowledgment);
        awaitScheduler();

        verify(deadLetterService).handle(eq(VALID_PAYLOAD), eq(ReasonCode.PUBLISH_ERROR), eq("msg-1"), eq("iid-1"));
        verify(acknowledgment, never()).acknowledge();
        verify(controlService, never()).recordPublished(any(), any());
    }
}
