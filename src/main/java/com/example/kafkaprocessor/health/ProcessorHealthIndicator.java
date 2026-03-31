package com.example.kafkaprocessor.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Reports health of the message processing thread pool.
 *
 * <p>Status rules:
 * <ul>
 *   <li>UP — pool utilization below 80%</li>
 *   <li>UNKNOWN — executor is not a ThreadPoolExecutor (unexpected config)</li>
 *   <li>OUT_OF_SERVICE — pool saturated (utilization >= 100%)</li>
 *   <li>DOWN — pool is shut down or terminating</li>
 * </ul>
 */
@Component("processorThreadPool")
public class ProcessorHealthIndicator implements HealthIndicator {

    private final ScheduledExecutorService processingScheduler;
    private final int workerThreads;

    public ProcessorHealthIndicator(ScheduledExecutorService processingScheduler,
                                    @Value("${app.processing.worker-threads:24}") int workerThreads) {
        this.processingScheduler = processingScheduler;
        this.workerThreads = workerThreads;
    }

    @Override
    public Health health() {
        if (processingScheduler.isShutdown() || processingScheduler.isTerminated()) {
            return Health.down()
                    .withDetail("reason", "processingScheduler is shut down")
                    .build();
        }

        if (!(processingScheduler instanceof ThreadPoolExecutor tpe)) {
            return Health.unknown()
                    .withDetail("reason", "processingScheduler is not a ThreadPoolExecutor")
                    .build();
        }

        int active      = tpe.getActiveCount();
        int poolSize    = tpe.getPoolSize();
        int queueSize   = tpe.getQueue().size();
        long completed  = tpe.getCompletedTaskCount();
        double utilization = workerThreads > 0 ? (double) active / workerThreads : 0.0;

        Health.Builder builder = utilization >= 1.0
                ? Health.outOfService().withDetail("reason", "thread pool saturated")
                : Health.up();

        return builder
                .withDetail("configuredThreads", workerThreads)
                .withDetail("activeThreads",     active)
                .withDetail("poolSize",          poolSize)
                .withDetail("queuedTasks",       queueSize)
                .withDetail("completedTasks",    completed)
                .withDetail("utilizationPct",    Math.round(utilization * 100) + "%")
                .build();
    }
}
