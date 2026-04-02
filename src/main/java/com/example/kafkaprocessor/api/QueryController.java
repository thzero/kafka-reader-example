package com.example.kafkaprocessor.api;

import com.example.kafkaprocessor.control.PublishedRecord;
import com.example.kafkaprocessor.control.PublishedRecordRepository;
import com.example.kafkaprocessor.control.ReceivedRecord;
import com.example.kafkaprocessor.control.ReceivedRecordRepository;
import com.example.kafkaprocessor.deadletter.DeadLetterRecord;
import com.example.kafkaprocessor.deadletter.DeadLetterRepository;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api")
public class QueryController {

    private final ReceivedRecordRepository receivedRecordRepository;
    private final PublishedRecordRepository publishedRecordRepository;
    private final DeadLetterRepository deadLetterRepository;

    public QueryController(ReceivedRecordRepository receivedRecordRepository,
                           PublishedRecordRepository publishedRecordRepository,
                           DeadLetterRepository deadLetterRepository) {
        this.receivedRecordRepository = receivedRecordRepository;
        this.publishedRecordRepository = publishedRecordRepository;
        this.deadLetterRepository = deadLetterRepository;
    }

    @GetMapping("/control/inbound")
    public List<ReceivedRecord> getInbound(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTimestamp,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTimestamp) {

        Instant from = TimeRangeHelper.resolveStart(startTimestamp);
        if (endTimestamp != null) {
            return receivedRecordRepository.findByReceivedAtBetween(from, endTimestamp);
        }
        return receivedRecordRepository.findByReceivedAtGreaterThanEqual(from);
    }

    @GetMapping("/control/outbound")
    public List<PublishedRecord> getOutbound(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTimestamp,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTimestamp) {

        Instant from = TimeRangeHelper.resolveStart(startTimestamp);
        if (endTimestamp != null) {
            return publishedRecordRepository.findByPublishedAtBetween(from, endTimestamp);
        }
        return publishedRecordRepository.findByPublishedAtGreaterThanEqual(from);
    }

    @GetMapping("/deadletter")
    public List<DeadLetterRecord> getDeadLetter(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTimestamp,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTimestamp) {

        Instant from = TimeRangeHelper.resolveStart(startTimestamp);
        if (endTimestamp != null) {
            return deadLetterRepository.findByFailedAtBetween(from, endTimestamp);
        }
        return deadLetterRepository.findByFailedAtGreaterThanEqual(from);
    }
}
