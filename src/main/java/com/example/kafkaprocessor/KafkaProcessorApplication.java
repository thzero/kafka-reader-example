package com.example.kafkaprocessor;

import com.example.kafkaprocessor.config.AppProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class KafkaProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaProcessorApplication.class, args);
    }
}
