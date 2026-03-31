package com.example.kafkaprocessor.kafka.siphon;

import com.example.kafkaprocessor.model.EventType;
import com.example.kafkaprocessor.model.KafkaMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link SiphonEvaluator} for Backdated Endorsements (BDE).
 *
 * <p>A message is a Backdated Endorsement when its {@code event.eventType} is
 * {@link EventType#END} and {@code event.backdated} is {@code true}.
 * Matching messages are routed to {@code kafka.topic.siphon-bde}.
 *
 * <p>Naming convention: {@code {EventCode}SiphonEvaluator} / {@code kafka.topic.siphon-{code}}.
 * Follow the same pattern to add new siphon routes.
 */
@Component
public class BdeSiphonEvaluator implements SiphonEvaluator {

    private final String siphonTopic;

    public BdeSiphonEvaluator(@Value("${kafka.topic.siphon-bde}") String siphonTopic) {
        this.siphonTopic = siphonTopic;
    }

    @Override
    public Optional<String> evaluate(KafkaMessage message) {
        if (message.event() == null) {
            return Optional.empty();
        }
        boolean match = EventType.END.equals(message.event().eventType())
                && Boolean.TRUE.equals(message.event().backdated());
        return match ? Optional.of(siphonTopic) : Optional.empty();
    }
}
