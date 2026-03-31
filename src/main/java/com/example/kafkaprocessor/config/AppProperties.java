package com.example.kafkaprocessor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Typed binding for all {@code app.*} configuration properties.
 *
 * <p>Registered via {@code @EnableConfigurationProperties(AppProperties.class)} on the
 * main application class. Inject this bean wherever app settings are needed in preference
 * to scattered {@code @Value} annotations.
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Processing processing = new Processing();
    private Siphon siphon = new Siphon();

    public Processing getProcessing() { return processing; }
    public void setProcessing(Processing processing) { this.processing = processing; }

    public Siphon getSiphon() { return siphon; }
    public void setSiphon(Siphon siphon) { this.siphon = siphon; }

    public static class Processing {

        /** Milliseconds to wait before processing each message. 0 disables the delay. */
        private long delayMs = 20000;

        /** Worker thread pool size. Size to: msg/sec × delay-ms / 1000. */
        private int workerThreads = 240;

        public long getDelayMs() { return delayMs; }
        public void setDelayMs(long delayMs) { this.delayMs = delayMs; }

        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
    }

    public static class Siphon {

        /**
         * Event codes of the {@link com.example.kafkaprocessor.kafka.siphon.SiphonEvaluator}
         * implementations to activate. Matches {@code SiphonEvaluator.eventCode()}.
         * An empty list activates all registered evaluators.
         * Example: {@code [bde]}
         */
        private List<String> enabled = List.of();

        public List<String> getEnabled() { return enabled; }
        public void setEnabled(List<String> enabled) { this.enabled = enabled; }
    }
}
