package com.example.kafkametrics.kafka;

import com.example.kafkametrics.processor.IEventProcessor;
import com.example.kafkametrics.model.KafkaMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Routes an incoming message to the appropriate {@link IEventProcessor} by {@code event.eventType}.
 *
 * <p>Registration is automatic — all Spring beans implementing {@link IEventProcessor} are
 * collected at startup. One must have {@code eventCode() == "*"} to serve as the fallback
 * (currently {@link com.example.kafkametrics.processor.DefaultEventProcessor}).
 *
 * <p>To add processing for a new event type, implement {@link IEventProcessor}, annotate with
 * {@code @Component}, and set {@code eventCode()} to the target eventType string. No changes
 * to this class are required.
 */
@Service
public class MessageProcessorService {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessorService.class);

    private final Map<String, IEventProcessor> processorsByCode;
    private final IEventProcessor defaultProcessor;

    public MessageProcessorService(List<IEventProcessor> processors) {
        this.defaultProcessor = processors.stream()
                .filter(p -> "*".equals(p.eventCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No default EventProcessor (eventCode=\"*\") registered"));
        this.processorsByCode = processors.stream()
                .filter(p -> !"*".equals(p.eventCode()))
                .collect(Collectors.toMap(IEventProcessor::eventCode, p -> p));
        log.info("Registered event processors: codes={} default={}",
                processorsByCode.keySet(), defaultProcessor.getClass().getSimpleName());
    }

    /**
     * Routes the message to the appropriate {@link IEventProcessor} by eventType, falling back
     * to the default processor if no specific handler is registered.
     *
     * <p>The processor is responsible for both transforming the message and calling
     * {@link KafkaProducerService#publish} with the result.
     *
     * @throws KafkaPublishException if the processor's publish step fails
     * @throws ProcessingException   if business logic within the processor fails
     */
    public void process(KafkaMessage message) {
        String eventType = message.header().eventType();
        IEventProcessor processor = processorsByCode.getOrDefault(eventType, defaultProcessor);
        log.debug("Routing eventType={} to processor={}", eventType, processor.getClass().getSimpleName());
        processor.process(message.header(), message.payload());
    }
}
