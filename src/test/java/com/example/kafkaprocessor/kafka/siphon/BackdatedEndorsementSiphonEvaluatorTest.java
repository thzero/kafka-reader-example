package com.example.kafkaprocessor.kafka.siphon;

import com.example.kafkaprocessor.model.EventHeader;
import com.example.kafkaprocessor.model.EventType;
import com.example.kafkaprocessor.model.KafkaMessage;
import com.example.kafkaprocessor.model.MessageBody;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BackdatedEndorsementSiphonEvaluatorTest {

    private static final String TOPIC = "siphon-topic";
    private final SiphonEvaluator evaluator = new BackdatedEndorsementSiphonEvaluator(TOPIC);

    private KafkaMessage message(String eventType, Boolean backdated) {
        return new KafkaMessage(new EventHeader("iid", eventType, backdated), new MessageBody("msg-1"));
    }

    @Test
    void endAndBackdatedTrue_returnsTopicName() {
        assertThat(evaluator.evaluate(message(EventType.END, true)))
                .isEqualTo(Optional.of(TOPIC));
    }

    @Test
    void endAndBackdatedFalse_returnsEmpty() {
        assertThat(evaluator.evaluate(message(EventType.END, false))).isEmpty();
    }

    @Test
    void endAndBackdatedNull_returnsEmpty() {
        assertThat(evaluator.evaluate(message(EventType.END, null))).isEmpty();
    }

    @Test
    void nonEndEventType_backdatedTrue_returnsEmpty() {
        assertThat(evaluator.evaluate(message(EventType.NC, true))).isEmpty();
        assertThat(evaluator.evaluate(message(EventType.TRM, true))).isEmpty();
        assertThat(evaluator.evaluate(message(EventType.RNW, true))).isEmpty();
    }

    @Test
    void nullEvent_returnsEmpty() {
        KafkaMessage msg = new KafkaMessage(null, new MessageBody("msg-1"));
        assertThat(evaluator.evaluate(msg)).isEmpty();
    }
}
