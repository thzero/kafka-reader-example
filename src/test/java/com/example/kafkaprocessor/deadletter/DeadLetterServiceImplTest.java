package com.example.kafkaprocessor.deadletter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(DeadLetterServiceImpl.class)
class DeadLetterServiceImplTest {

    @Autowired
    private DeadLetterService deadLetterService;

    @Autowired
    private DeadLetterRepository repository;

    @Test
    void handle_persistsRecordWithCorrectFields() {
        deadLetterService.handle("{\"event\":{}}", ReasonCode.PROCESSING_ERROR, "msg-1", "interaction-1");

        List<DeadLetterRecord> records = repository.findAll();
        assertThat(records).hasSize(1);
        DeadLetterRecord record = records.get(0);
        assertThat(record.getRawPayload()).isEqualTo("{\"event\":{}}");
        assertThat(record.getReasonCode()).isEqualTo(ReasonCode.PROCESSING_ERROR);
        assertThat(record.getMessageId()).isEqualTo("msg-1");
        assertThat(record.getInteractionId()).isEqualTo("interaction-1");
        assertThat(record.getFailedAt()).isNotNull();
    }

    @Test
    void handle_allowsNullMessageIdAndInteractionId() {
        deadLetterService.handle("bad-json", ReasonCode.DESERIALIZATION_ERROR, null, null);

        List<DeadLetterRecord> records = repository.findAll();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getMessageId()).isNull();
        assertThat(records.get(0).getInteractionId()).isNull();
    }

    @Test
    void findByFailedAtGreaterThanEqual_returnsRecordsInRange() {
        Instant before = Instant.now().minusSeconds(60);
        deadLetterService.handle("{}", ReasonCode.PUBLISH_ERROR, "msg-2", "interaction-2");

        List<DeadLetterRecord> results = repository.findByFailedAtGreaterThanEqual(before);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getReasonCode()).isEqualTo(ReasonCode.PUBLISH_ERROR);
    }
}
