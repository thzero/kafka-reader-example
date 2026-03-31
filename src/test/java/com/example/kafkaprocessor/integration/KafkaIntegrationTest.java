package com.example.kafkaprocessor.integration;

import com.example.kafkaprocessor.KafkaProcessorApplication;
import com.example.kafkaprocessor.control.ReceivedRecordRepository;
import com.example.kafkaprocessor.control.PublishedRecordRepository;
import com.example.kafkaprocessor.model.EventHeader;
import com.example.kafkaprocessor.model.KafkaMessage;
import com.example.kafkaprocessor.model.MessageBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = KafkaProcessorApplication.class)
@ActiveProfiles("integration")
@EmbeddedKafka(partitions = 1, topics = {"test-input-topic", "test-output-topic", "test-siphon-bde-topic"})
@TestPropertySource(properties = {
        "kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "kafka.consumer.processing-delay-ms=0"
})
class KafkaIntegrationTest {

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private ReceivedRecordRepository receivedRecordRepository;

    @Autowired
    private PublishedRecordRepository publishedRecordRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${kafka.topic.input}")
    private String inputTopic;

    @Value("${kafka.topic.output}")
    private String outputTopic;

    @Value("${kafka.topic.siphon-bde}")
    private String siphonBdeTopic;

    @Test
    void messageFlowProducesOutputAndWritesControlRecords() throws Exception {
        // Arrange: set up a consumer on the output topic to capture published messages
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "test-consumer-group", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        BlockingQueue<ConsumerRecord<String, String>> outputRecords = new LinkedBlockingQueue<>();
        DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(consumerProps);
        ContainerProperties containerProps = new ContainerProperties(outputTopic);
        KafkaMessageListenerContainer<String, String> container =
                new KafkaMessageListenerContainer<>(cf, containerProps);
        container.setupMessageListener((MessageListener<String, String>) outputRecords::add);
        container.start();
        ContainerTestUtils.waitForAssignment(container, embeddedKafkaBroker.getPartitionsPerTopic());

        // Act: publish a message to the input topic
        KafkaMessage inputMessage = new KafkaMessage(
                new EventHeader("iid-integration", "TEST", null),
                new MessageBody("a0000000-0000-0000-0000-000000000001"));
        String payload = objectMapper.writeValueAsString(inputMessage);

        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String> pf =
                new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(producerProps);
        org.springframework.kafka.core.KafkaTemplate<String, String> template =
                new org.springframework.kafka.core.KafkaTemplate<>(pf);
        template.send(inputTopic, payload);

        // Assert: message appears on output topic
        ConsumerRecord<String, String> received = outputRecords.poll(10, TimeUnit.SECONDS);
        assertThat(received).isNotNull();

        KafkaMessage outputMessage = objectMapper.readValue(received.value(), KafkaMessage.class);
        assertThat(outputMessage.body().messageId()).isEqualTo("a0000000-0000-0000-0000-000000000001");

        // Assert: RECEIVED and PUBLISHED control records exist
        assertThat(receivedRecordRepository.existsByMessageId("a0000000-0000-0000-0000-000000000001")).isTrue();
        assertThat(publishedRecordRepository.existsByMessageId("a0000000-0000-0000-0000-000000000001")).isTrue();

        container.stop();
        pf.destroy();
    }

    @Test
    void bdeMessageIsSiphonedAndDoesNotEnterPipeline() throws Exception {
        // Arrange: listen on the siphon-bde topic
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "test-siphon-consumer-group", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        BlockingQueue<ConsumerRecord<String, String>> siphonRecords = new LinkedBlockingQueue<>();
        DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(consumerProps);
        ContainerProperties containerProps = new ContainerProperties(siphonBdeTopic);
        KafkaMessageListenerContainer<String, String> container =
                new KafkaMessageListenerContainer<>(cf, containerProps);
        container.setupMessageListener((MessageListener<String, String>) siphonRecords::add);
        container.start();
        ContainerTestUtils.waitForAssignment(container, embeddedKafkaBroker.getPartitionsPerTopic());

        // Act: publish a BDE message (END + backdated=true) to the input topic
        KafkaMessage bdeMessage = new KafkaMessage(
                new EventHeader("iid-bde-integration", "END", true),
                new MessageBody("b0000000-0000-0000-0000-000000000001"));
        String payload = objectMapper.writeValueAsString(bdeMessage);

        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String> pf =
                new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(producerProps);
        org.springframework.kafka.core.KafkaTemplate<String, String> template =
                new org.springframework.kafka.core.KafkaTemplate<>(pf);
        template.send(inputTopic, payload);

        // Assert: message appears on the siphon topic
        ConsumerRecord<String, String> siphoned = siphonRecords.poll(10, TimeUnit.SECONDS);
        assertThat(siphoned).isNotNull();
        KafkaMessage siphonedMessage = objectMapper.readValue(siphoned.value(), KafkaMessage.class);
        assertThat(siphonedMessage.body().messageId()).isEqualTo("b0000000-0000-0000-0000-000000000001");
        assertThat(siphonedMessage.event().eventType()).isEqualTo("END");
        assertThat(siphonedMessage.event().backdated()).isEqualTo(Boolean.TRUE);

        // Assert: BDE message did NOT enter the processing pipeline
        assertThat(receivedRecordRepository.existsByMessageId("b0000000-0000-0000-0000-000000000001")).isFalse();
        assertThat(publishedRecordRepository.existsByMessageId("b0000000-0000-0000-0000-000000000001")).isFalse();

        container.stop();
        pf.destroy();
    }
}
