package com.example.kafkametrics.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.example.kafkametrics.kafka.KafkaProducerService;
import com.example.kafkametrics.model.EventHeader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DefaultEventProcessorTest {

    @Mock private KafkaProducerService publisher;

    private DefaultEventProcessor processor;
    private ObjectMapper objectMapper;

    private static final String OUTPUT_TOPIC = "output-topic";
    private static final String MSG_ID = "00000000-0000-0000-0000-000000000001";

    private static final String SOURCE_SYSTEM = "TEST-SYS";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        processor = new DefaultEventProcessor(publisher, objectMapper);
        DefaultEventProcessor p = processor;
        ReflectionTestUtils.setField(p, "outputTopic", OUTPUT_TOPIC);
        ReflectionTestUtils.setField(p, "sourceSystemEntCd", SOURCE_SYSTEM);
    }

    @Test
    void eventCode_returnsWildcard() {
        assertThat(processor.eventCode()).isEqualTo("*");
    }

    @Test
    void process_publishesEnvelopeToOutputTopic() {
        JsonNode inputPayload = objectMapper.createObjectNode().put("someField", "someValue");

        processor.process(new EventHeader(MSG_ID, "iid-1", "NC", null, null), inputPayload);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<JsonNode> payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(keyCaptor.capture(), payloadCaptor.capture(), topicCaptor.capture());

        assertThat(keyCaptor.getValue()).isEqualTo(MSG_ID);
        assertThat(topicCaptor.getValue()).isEqualTo(OUTPUT_TOPIC);
        JsonNode header = payloadCaptor.getValue().get("header");
        assertThat(header.get("messageId").asText()).isEqualTo(MSG_ID);
        assertThat(header.get("interactionId").asText()).isEqualTo("iid-1");
        assertThat(header.get("sourceSystemEntCd").asText()).isEqualTo(SOURCE_SYSTEM);
        assertThat(payloadCaptor.getValue().get("payload").get("someField").asText()).isEqualTo("someValue");
    }

    @Test
    void process_withNullMessageId_usesNullKey() {
        processor.process(new EventHeader(null, "iid-2", "TRM", null, null), objectMapper.createObjectNode());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(keyCaptor.capture(), ArgumentMatchers.<JsonNode>any(), ArgumentMatchers.any());
        assertThat(keyCaptor.getValue()).isNull();
    }
}
