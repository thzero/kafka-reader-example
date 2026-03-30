package com.example.kafkaprocessor.kafka.siphon;

import com.example.kafkaprocessor.model.EventHeader;
import com.example.kafkaprocessor.model.EventType;
import com.example.kafkaprocessor.model.KafkaMessage;
import com.example.kafkaprocessor.model.MessageBody;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackdatedEndorsementSiphonEvaluatorTest {

    private final SiphonEvaluator evaluator = new BackdatedEndorsementSiphonEvaluator();

    private KafkaMessage message(String eventType, Boolean backdated) {
        return new KafkaMessage(new EventHeader("iid", eventType, backdated), new MessageBody("msg-1"));
    }

    @Test
    void endAndBackdatedTrue_returnTrue() {
        assertThat(evaluator.shouldSiphon(message(EventType.END, true))).isTrue();
    }

    @Test
    void endAndBackdatedFalse_returnFalse() {
        assertThat(evaluator.shouldSiphon(message(EventType.END, false))).isFalse();
    }

    @Test
    void endAndBackdatedNull_returnFalse() {
        assertThat(evaluator.shouldSiphon(message(EventType.END, null))).isFalse();
    }

    @Test
    void nonEndEventType_backdatedTrue_returnFalse() {
        assertThat(evaluator.shouldSiphon(message(EventType.NC, true))).isFalse();
        assertThat(evaluator.shouldSiphon(message(EventType.TRM, true))).isFalse();
        assertThat(evaluator.shouldSiphon(message(EventType.RNW, true))).isFalse();
    }

    @Test
    void nullEvent_returnFalse() {
        KafkaMessage msg = new KafkaMessage(null, new MessageBody("msg-1"));
        assertThat(evaluator.shouldSiphon(msg)).isFalse();
    }
}
