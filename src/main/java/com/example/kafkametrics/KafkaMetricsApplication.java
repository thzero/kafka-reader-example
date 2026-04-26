package com.example.kafkametrics;

import com.example.kafkametrics.config.AppProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class KafkaMetricsApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaMetricsApplication.class, args);
    }
}
