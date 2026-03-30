package com.example.kafkaprocessor.control;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(ControlServiceImpl.class)
class ControlServiceImplTest {

    @Autowired
    private ControlService controlService;

    @Autowired
    private ReceivedRecordRepository receivedRepository;

    @Autowired
    private PublishedRecordRepository publishedRepository;

    @Test
    void recordReceived_persistsRecordWithCorrectFields() {
        controlService.recordReceived("msg-1", "interaction-1");

        List<ReceivedRecord> records = receivedRepository.findAll();
        assertThat(records).hasSize(1);
        ReceivedRecord record = records.get(0);
        assertThat(record.getMessageId()).isEqualTo("msg-1");
        assertThat(record.getInteractionId()).isEqualTo("interaction-1");
        assertThat(record.getReceivedAt()).isNotNull();
    }

    @Test
    void recordReceived_duplicateMessageId_throwsDataIntegrityViolation() {
        controlService.recordReceived("msg-dup", "interaction-1");

        assertThatThrownBy(() -> controlService.recordReceived("msg-dup", "interaction-2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recordPublished_persistsRecordWithCorrectFields() {
        controlService.recordPublished("msg-2", "interaction-2");

        List<PublishedRecord> records = publishedRepository.findAll();
        assertThat(records).hasSize(1);
        PublishedRecord record = records.get(0);
        assertThat(record.getMessageId()).isEqualTo("msg-2");
        assertThat(record.getInteractionId()).isEqualTo("interaction-2");
        assertThat(record.getPublishedAt()).isNotNull();
    }

    @Test
    void findByReceivedAtGreaterThanEqual_returnsRecordsInRange() {
        Instant before = Instant.now().minusSeconds(60);
        controlService.recordReceived("msg-3", "interaction-3");

        List<ReceivedRecord> results = receivedRepository.findByReceivedAtGreaterThanEqual(before);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getMessageId()).isEqualTo("msg-3");
    }
}
