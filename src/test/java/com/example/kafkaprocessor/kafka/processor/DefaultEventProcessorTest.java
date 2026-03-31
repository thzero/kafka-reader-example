package com.example.kafkaprocessor.kafka.processor;

import com.example.kafkaprocessor.kafka.KafkaProducerService;
import com.example.kafkaprocessor.model.EventHeader;
import com.example.kafkaprocessor.model.KafkaMessage;
import com.example.kafkaprocessor.model.MessageBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DefaultEventProcessorTest {

    @Mock private KafkaProducerService publisher;

    private DefaultEventProcessor processor;
    private ObjectMapper objectMapper;

    private static final String OUTPUT_TOPIC = "output-topic";
    private static final String MSG_ID = "00000000-0000-0000-0000-000000000001";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        processor = new DefaultEventProcessor(publisher, objectMapper);
        ReflectionTestUtils.setField(processor, "outputTopic", OUTPUT_TOPIC);
    }

    @Test
    void eventCode_returnsWildcard() {
        assertThat(processor.eventCode()).isEqualTo("*");
    }

    @Test
    void process_publishesSerializedMessageToOutputTopic() throws Exception {
        KafkaMessage message = new KafkaMessage(
                new EventHeader("iid-1", "NC", null),
                new MessageBody(MSG_ID));
        String rawPayload = objectMapper.writeValueAsString(message);

        processor.process(message, rawPayload);

        ArgumentCaptor<String> keyCaptor     = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> topicCaptor   = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(keyCaptor.capture(), payloadCaptor.capture(), topicCaptor.capture());

        assertThat(keyCaptor.getValue()).isEqualTo(MSG_ID);
        assertThat(topicCaptor.getValue()).isEqualTo(OUTPUT_TOPIC);

        // Payload must be valid JSON containing the messageId
        KafkaMessage parsed = objectMapper.readValue(payloadCaptor.getValue(), KafkaMessage.class);
        assertThat(parsed.body().messageId()).isEqualTo(MSG_ID);
        assertThat(parsed.event().eventType()).isEqualTo("NC");
    }

    @Test
    void process_withNullBodyMessageId_usesNullKey() throws Exception {
        KafkaMessage message = new KafkaMessage(new EventHeader("iid-2", "TRM", null), null);
        String rawPayload = objectMapper.writeValueAsString(message);

        processor.process(message, rawPayload);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(keyCaptor.capture(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(keyCaptor.getValue()).isNull();
    }
}
