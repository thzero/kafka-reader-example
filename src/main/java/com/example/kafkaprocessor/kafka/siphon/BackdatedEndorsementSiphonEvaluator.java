package com.example.kafkaprocessor.kafka.siphon;

import com.example.kafkaprocessor.model.EventType;
import com.example.kafkaprocessor.model.KafkaMessage;
import org.springframework.stereotype.Component;

/**
 * Default {@link SiphonEvaluator} — siphons Backdated Endorsements.
 *
 * <p>A message is a Backdated Endorsement when its {@code event.eventType} is
 * {@link EventType#END} and {@code event.backdated} is {@code true}.
 */
@Component
public class BackdatedEndorsementSiphonEvaluator implements SiphonEvaluator {

    @Override
    public boolean shouldSiphon(KafkaMessage message) {
        if (message.event() == null) {
            return false;
        }
        return EventType.END.equals(message.event().eventType())
                && Boolean.TRUE.equals(message.event().backdated());
    }
}
