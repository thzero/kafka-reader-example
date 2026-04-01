package com.example.kafkaprocessor.kafka;

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

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    private static final String MSG_ID_1 = "00000000-0000-0000-0000-000000000001";
    private static final String IID_1    = "iid-1";

    private static final String VALID_PAYLOAD =
            "{\"event\":{\"interactionId\":\"iid-1\",\"eventType\":\"TEST\"},\"body\":{\"messageId\":\"00000000-0000-0000-0000-000000000001\"}}";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new KafkaConsumerListener(
                objectMapper, controlService,
                messageProcessorService, kafkaProducerService, deadLetterService,
                List.of(siphonEvaluator), new SimpleMeterRegistry());
    }

    private ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("input-topic", 0, 0L, null, payload);
    }

    @Test
    void happyPath_writesControlRecords_publishesAndAcks() {
        // messageProcessorService.process() is void — default mock behaviour (do nothing) is correct.
        // The actual publish is done inside the EventProcessor, not in the listener.
        listener.listen(record(VALID_PAYLOAD), acknowledgment);

        verify(controlService).recordReceived(MSG_ID_1, IID_1);
        verify(messageProcessorService).process(any());
        verify(controlService).recordPublished(MSG_ID_1, IID_1);
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(deadLetterService);
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
    void deserializationFailure_routesToDeadLetter_noAck() {
        listener.listen(record("not-valid-json"), acknowledgment);

        verify(deadLetterService).handle(eq("not-valid-json"), eq(ReasonCode.DESERIALIZATION_ERROR), isNull(), isNull());
        verify(acknowledgment, never()).acknowledge();
        verifyNoInteractions(controlService, messageProcessorService, kafkaProducerService);
    }

    @Test
    void bdeEvent_siphonsToSiphonTopic_acks() {
        String bdePayload = "{\"event\":{\"interactionId\":\"iid-2\",\"eventType\":\"END\",\"backdated\":true},\"body\":{\"messageId\":\"00000000-0000-0000-0000-0000000000bd\"}}";
        when(siphonEvaluator.evaluate(any())).thenReturn(java.util.Optional.of("test-siphon-topic"));

        listener.listen(record(bdePayload), acknowledgment);

        verify(kafkaProducerService).publish(eq("00000000-0000-0000-0000-0000000000bd"), eq(bdePayload), eq("test-siphon-topic"));
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(controlService, messageProcessorService, deadLetterService);
    }

    @Test
    void bdeEvent_siphonFailure_noAck() {
        String bdePayload = "{\"event\":{\"interactionId\":\"iid-2\",\"eventType\":\"END\",\"backdated\":true},\"body\":{\"messageId\":\"00000000-0000-0000-0000-0000000000bd\"}}";
        when(siphonEvaluator.evaluate(any())).thenReturn(java.util.Optional.of("test-siphon-topic"));
        doThrow(new KafkaPublishException("siphon failed", new RuntimeException()))
                .when(kafkaProducerService).publish(any(), anyString(), any());

        listener.listen(record(bdePayload), acknowledgment);

        verify(acknowledgment, never()).acknowledge();
        verifyNoInteractions(controlService, messageProcessorService, deadLetterService);
    }

    @Test
    void processingFailure_routesToDeadLetter_noAck() {
        doThrow(new ProcessingException("boom")).when(messageProcessorService).process(any());

        listener.listen(record(VALID_PAYLOAD), acknowledgment);

        verify(deadLetterService).handle(eq(VALID_PAYLOAD), eq(ReasonCode.PROCESSING_ERROR), eq(MSG_ID_1), eq(IID_1));
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void publishFailure_routesToDeadLetter_noAck() {
        doThrow(new KafkaPublishException("publish failed", new RuntimeException()))
                .when(messageProcessorService).process(any());

        listener.listen(record(VALID_PAYLOAD), acknowledgment);

        verify(deadLetterService).handle(eq(VALID_PAYLOAD), eq(ReasonCode.PUBLISH_ERROR), eq(MSG_ID_1), eq(IID_1));
        verify(acknowledgment, never()).acknowledge();
        verify(controlService, never()).recordPublished(any(), any());
    }
}
