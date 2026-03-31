package com.example.kafkaprocessor.kafka;

import com.example.kafkaprocessor.config.AppProperties;
import com.example.kafkaprocessor.kafka.siphon.SiphonEvaluator;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class KafkaConsumerConfig {

    @Value("${kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${kafka.consumer.group-id}")
    private String groupId;

    @Value("${kafka.consumer.concurrency}")
    private int concurrency;

    @Value("${app.processing.worker-threads:24}")
    private int workerThreads;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    // Scheduled thread pool used to defer message processing by the configured delay.
    // The consumer thread schedules work here and returns immediately, allowing the
    // consumer to keep pulling messages while prior messages wait out their delay.
    // Size this to: expected in-flight messages during the delay window.
    // Example: 100 msg/sec * 20s delay = 2000 in-flight → set worker-threads >= 2000.
    @Bean
    public ScheduledExecutorService processingScheduler() {
        return Executors.newScheduledThreadPool(workerThreads);
    }

    /**
     * Builds the ordered list of {@link SiphonEvaluator}s active at runtime.
     *
     * <p>All registered evaluators are collected via {@link ObjectProvider}. If
     * {@code app.siphon.enabled} is non-empty, only evaluators whose
     * {@link SiphonEvaluator#eventCode()} appears in that list are retained.
     */
    @Bean
    public List<SiphonEvaluator> activeSiphonEvaluators(
            ObjectProvider<SiphonEvaluator> allEvaluators,
            AppProperties appProperties) {
        List<SiphonEvaluator> all = allEvaluators.stream().toList();
        List<String> enabled = appProperties.getSiphon().getEnabled();
        if (enabled.isEmpty()) {
            return all;
        }
        return all.stream()
                .filter(e -> enabled.contains(e.eventCode()))
                .toList();
    }
}
