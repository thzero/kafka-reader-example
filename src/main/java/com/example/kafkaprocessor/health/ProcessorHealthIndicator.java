package com.example.kafkaprocessor.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("processorThreadPool")
public class ProcessorHealthIndicator implements HealthIndicator {

    @Value("${kafka.consumer.concurrency:1}")
    private int concurrency;

    @Override
    public Health health() {
        return Health.up()
                .withDetail("mode", "synchronous — consumer thread blocks until each message is fully processed and acked")
                .withDetail("consumerConcurrency", concurrency)
                .build();
    }
}
