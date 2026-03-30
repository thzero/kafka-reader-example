# Build Plan: Kafka Processor Application

## Overview

A Spring Boot 3.x / Java 21 application built with Gradle (Groovy DSL) that consumes JSON messages from a Kafka input topic, processes them concurrently, publishes results to a Kafka output topic, persists control records to an in-memory H2 database, and routes failures to a Dead Letter component. Deployed as 10 instances sharing a common Kafka `groupId`.

---

## Project Configuration

| Setting      | Value                        |
|--------------|------------------------------|
| Language     | Java 21                      |
| Framework    | Spring Boot 3.x (latest)     |
| Build Tool   | Gradle (Groovy DSL)          |
| Base Package | `com.example.kafkaprocessor` |
| Database     | H2 (in-memory, swappable)    |

---

## Phase 1 — Project Scaffold
*All steps parallel*

1. Initialize Gradle Groovy DSL project (`build.gradle`, `settings.gradle`)
2. Define dependencies in `build.gradle`:
   - `spring-boot-starter`
   - `spring-kafka`
   - `spring-boot-starter-data-jpa`
   - `com.h2database:h2`
   - `jackson-databind` (transitive via Spring)
   - `spring-boot-starter-web` (REST API)
   - `logstash-logback-encoder` (structured JSON logging)
3. Create `application.yml` with all externalized config placeholders:
   - `kafka.bootstrap-servers`, `kafka.consumer.group-id`
   - `kafka.topic.input`, `kafka.topic.output`
   - `kafka.consumer.concurrency` — set to `partitions ÷ instances` (e.g., 10 partitions / 10 instances = `1`; higher values waste idle threads)
   - `spring.datasource` (H2 in-memory)
   - `app.processing.delay-ms` (default: `20000`) — delay before processing each message; kept under `app.*` not `kafka.*` because this is our code, not a Kafka setting
   - `app.processing.worker-threads` (default: `240`) — thread pool size for the scheduled executor; size to peak in-flight messages = msg/sec × delay-ms / 1000
4. Create base package directory tree under `src/main/java/com/example/kafkaprocessor/`

**Relevant files:** `build.gradle`, `settings.gradle`, `src/main/resources/application.yml`

---

## Phase 2 — Message Model
*All steps parallel*

1. Create `EventHeader` record — fields: `interactionId` (String), `eventType` (String)
2. Create `MessageBody` record — field: `messageId` (String)
3. Create `KafkaMessage` envelope — fields: `event` (EventHeader), `body` (MessageBody)
4. Annotate all classes with Jackson `@JsonProperty`

**Package:** `com.example.kafkaprocessor.model`

**Relevant files:**
- `model/EventHeader.java`
- `model/MessageBody.java`
- `model/KafkaMessage.java`

---

## Phase 3 — Logging & MDC Configuration
*Depends on nothing; parallel with Phase 2*

1. Configure `logback-spring.xml` — structured JSON to stdout via `logstash-logback-encoder`;
   include fields: `timestamp`, `level`, `interactionId` (MDC), `messageId` (MDC), `message`
2. Create `MdcContext` utility — static helpers to set/clear `interactionId` and `messageId` in MDC
3. MDC must be set immediately after deserialization in the consumer and cleared in a `finally` block

**Package:** `com.example.kafkaprocessor.logging`

**Relevant files:**
- `src/main/resources/logback-spring.xml`
- `logging/MdcContext.java`

---

## Phase 4 — Control Component
*Depends on Phase 2*

1. Create **two** JPA entities in `com.example.kafkaprocessor.control`:
   - `ReceivedRecord` — `@Table(name = "received_record")` with `@UniqueConstraint` on `message_id`
     - Fields: `id` (auto), `messageId` (unique, not null), `interactionId`, `receivedAt` (Instant, not null)
   - `PublishedRecord` — `@Table(name = "published_record")`, no unique constraint
     - Fields: `id` (auto), `messageId`, `interactionId`, `publishedAt` (Instant)
2. Create `ReceivedRecordRepository extends JpaRepository<ReceivedRecord, Long>` with `existsByMessageId`, `findByReceivedAtGreaterThanEqual`, `findByReceivedAtBetween`
3. Create `PublishedRecordRepository extends JpaRepository<PublishedRecord, Long>` with `existsByMessageId`, `findByPublishedAtGreaterThanEqual`, `findByPublishedAtBetween`
4. Create `ControlService` interface — methods: `recordReceived(String messageId, String interactionId) throws DataIntegrityViolationException`, `recordPublished(String messageId, String interactionId)`
5. Create `ControlServiceImpl` implementing `ControlService` — `recordReceived` uses `saveAndFlush()` so the unique constraint fires within the same transaction
6. Configure `spring.datasource.url=jdbc:h2:mem:controldb` in `application.yml`

**Package:** `com.example.kafkaprocessor.control`

**Relevant files:**
- `control/ReceivedRecord.java`
- `control/PublishedRecord.java`
- `control/ReceivedRecordRepository.java`
- `control/PublishedRecordRepository.java`
- `control/ControlService.java`
- `control/ControlServiceImpl.java`

---

## Phase 5 — Dead Letter Component
*Depends on Phase 2; parallel with Phase 4*

1. Create `ReasonCode` enum: `DESERIALIZATION_ERROR`, `PROCESSING_ERROR`,
   `PUBLISH_ERROR`, `CONTROL_RECORD_ERROR`, `DUPLICATE`
2. Create `DeadLetterRecord` JPA entity:
   - `id` (auto), `messageId` (nullable), `interactionId` (nullable),
     `rawPayload` (String), `reasonCode` (ReasonCode), `failedAt` (Instant)
3. Create `DeadLetterRepository extends JpaRepository<DeadLetterRecord, Long>`
4. Create `DeadLetterService` interface —
   method: `handle(String rawPayload, ReasonCode reasonCode, String messageId, String interactionId)`
5. Create `DeadLetterServiceImpl` — persists to H2; implementation is pluggable (swappable later)

**Package:** `com.example.kafkaprocessor.deadletter`

**Relevant files:**
- `deadletter/ReasonCode.java`
- `deadletter/DeadLetterRecord.java`
- `deadletter/DeadLetterRepository.java`
- `deadletter/DeadLetterService.java`
- `deadletter/DeadLetterServiceImpl.java`

---

## Phase 6 — Kafka Producer
*Depends on Phase 2; parallel with Phases 4 & 5*

1. Create `KafkaProducerConfig` bean:
   - `StringSerializer` for key, `JsonSerializer` for value
   - Bootstrap servers from properties
   - Enable **idempotent producer**: `enable.idempotence=true`
   - Set **transactional ID**: `transactional-id` from properties (unique per instance, e.g. `kafkaprocessor-tx-${instance-id}`)
2. Create `KafkaProducerService` wrapping `KafkaTemplate<String, KafkaMessage>`:
   - `publish(KafkaMessage message)` — publishes to configured output topic within a Kafka transaction, awaits result,
     throws on failure; logs success at INFO with `interactionId` and `messageId`

**Package:** `com.example.kafkaprocessor.kafka`

**Relevant files:**
- `kafka/KafkaProducerConfig.java`
- `kafka/KafkaProducerService.java`

---

## Phase 7 — Kafka Consumer & Message Processor
*Depends on Phases 3, 4, 5, 6*

1. Create `KafkaConsumerConfig` bean:
   - `enable.auto.commit=false`, `AckMode.MANUAL_IMMEDIATE`
   - **`isolation.level=read_committed`** — EOS; only reads messages from committed transactions
   - `ErrorHandlingDeserializer` wrapping `JsonDeserializer` for value
   - `groupId` and `concurrency` from properties
   - Create a `ScheduledExecutorService` bean (`processingScheduler`) with `app.processing.worker-threads` threads
2. Create `MessageProcessorService.process(KafkaMessage)` — business logic stub (no delay here)
   - Throws `ProcessingException` on any failure
3. Create `KafkaConsumerListener` (`@KafkaListener`):
   - Receives raw `String` payload (preserves original for dead letter use)
   - Manually deserializes to `KafkaMessage` via `ObjectMapper`
   - Sets MDC (`interactionId`, `messageId`)
   - **Calls `ControlService.recordReceived(...)` on the consumer thread** — if `DataIntegrityViolationException` is thrown, the message is a duplicate; route to `DeadLetterService` with `ReasonCode.DUPLICATE`, call `Acknowledgment.acknowledge()`, and return. Any other exception routes to `CONTROL_RECORD_ERROR` dead letter without ack.
   - Captures MDC context snapshot (MDC is thread-local; the worker thread must restore it)
   - **Schedules** the remaining work (process → publish → recordPublished → ack) via `ScheduledExecutorService` with `app.processing.delay-ms` delay — consumer thread returns immediately
   - The scheduled worker: restores MDC, calls `MessageProcessorService.process(...)`, calls `KafkaProducerService.publish(...)`, calls `ControlService.recordPublished(...)`, calls `Acknowledgment.acknowledge()` only on full success
   - A `ReceivedRecord` with no matching `PublishedRecord` indicates a processing failure — useful for reconciliation jobs
   - Wraps every stage in try/catch; routes to `DeadLetterService` with `ReasonCode` on failure
   - Clears MDC in `finally` block on both the consumer thread and the worker thread

**Package:** `com.example.kafkaprocessor.kafka`

**Relevant files:**
- `kafka/KafkaConsumerConfig.java`
- `kafka/KafkaConsumerListener.java`
- `kafka/MessageProcessorService.java`

---

## Phase 8 — Query REST API
*Depends on Phases 4 & 5*

1. Add `spring-boot-starter-web` dependency (already in Phase 1)
2. Create `QueryController` (`@RestController`, base path `/api`) with three endpoints:

   **`GET /api/control/inbound`**
   - Query params: `startTimestamp` (ISO-8601, optional — defaults to now minus 12 hours), `endTimestamp` (ISO-8601, optional)
   - Returns all `ReceivedRecord` entries where `receivedAt >= startTimestamp` and, if provided, `receivedAt <= endTimestamp`
   - Response fields per record: `messageId`, `interactionId`, `receivedAt`

   **`GET /api/control/outbound`**
   - Query params: `startTimestamp` (optional, default now − 12h), `endTimestamp` (optional)
   - Returns all `PublishedRecord` entries where `publishedAt >= startTimestamp` and, if provided, `publishedAt <= endTimestamp`
   - Response fields per record: `messageId`, `interactionId`, `publishedAt`

   **`GET /api/deadletter`**
   - Query params: `startTimestamp` (optional, default now − 12h), `endTimestamp` (optional)
   - Returns all `DeadLetterRecord` entries where `failedAt >= startTimestamp` and, if provided, `failedAt <= endTimestamp`
   - Response fields per record: `messageId`, `interactionId`, `reasonCode`, `rawPayload`, `failedAt`

3. Create `TimeRangeHelper` utility — resolves `startTimestamp` to now minus 12 hours when null; passes `endTimestamp` through as-is (null = open-ended)
4. Add `findByReceivedAtBetween` / `findByReceivedAtGreaterThanEqual` and `existsByMessageId` query methods to `ReceivedRecordRepository`
5. Add `findByPublishedAtBetween` / `findByPublishedAtGreaterThanEqual` and `existsByMessageId` equivalents to `PublishedRecordRepository`
6. Add `findByFailedAtBetween` / `findByFailedAtGreaterThanEqual` query methods to `DeadLetterRepository`
7. Add `server.port` to `application.yml`

**Package:** `com.example.kafkaprocessor.api`

**Relevant files:**
- `api/QueryController.java`
- `api/TimeRangeHelper.java`

---

## Phase 9 — Application Entry Point & Wiring
*Depends on all prior phases*

1. Create `KafkaProcessorApplication` with `@SpringBootApplication`
2. Verify all Spring beans wire without circular dependencies
3. Confirm `application.yml` covers all config keys with sensible local-dev defaults

**Relevant files:**
- `KafkaProcessorApplication.java`
- `src/main/resources/application.yml`

---

## Phase 10 — Testing

1. *(parallel)* Unit test `ControlServiceImpl` — verify `ReceivedRecord` persisted with correct fields and `receivedAt`; verify second call with same `messageId` throws `DataIntegrityViolationException`; verify `PublishedRecord` persisted with correct fields
2. *(parallel)* Unit test `DeadLetterServiceImpl` — verify record persisted with correct
   `reasonCode`, `rawPayload`, timestamps
3. *(parallel)* Unit test `KafkaConsumerListener` with mocked dependencies:
   - Happy path: MDC set, `recordReceived` called, publish called, `recordPublished` called, ack sent
   - Deserialization failure → `DESERIALIZATION_ERROR`, no ack
   - Duplicate `messageId` — `controlService.recordReceived()` throws `DataIntegrityViolationException` → `DUPLICATE`, ack and discard
   - Processing failure → `PROCESSING_ERROR`, no ack
   - Processing delay applied — assert message is scheduled with configured `app.processing.delay-ms` and that process/publish/ack run after the delay fires
   - Publish failure → `PUBLISH_ERROR`, no ack
4. Integration test with `@EmbeddedKafka`:
   - Produce message to input topic → assert on output topic
   - Assert `ReceivedRecord` and `PublishedRecord` exist in H2 via their respective repositories
5. Verify structured log output contains `interactionId` and `messageId` fields
6. *(parallel)* Unit test `QueryController`:
   - `GET /api/control/inbound` with no params — assert `startTimestamp` defaults to now minus 12 hours, `endTimestamp` is open-ended
   - `GET /api/control/inbound` with both params — assert range is applied correctly
   - `GET /api/control/outbound` with and without params
   - `GET /api/deadletter` with and without params
   - Assert correct HTTP 200 responses and response body field mapping

---

## Component Interaction Flow

```
Kafka Input Topic
      │
      ▼
KafkaConsumerListener
  ├─ Deserialize JSON → KafkaMessage
  ├─ Set MDC (interactionId, messageId)
  ├─ ControlService.recordReceived()
  ├─ MessageProcessorService.process()
  ├─ KafkaProducerService.publish()
  ├─ ControlService.recordPublished()
  └─ Acknowledgment.acknowledge()
       │
       └─ [ANY EXCEPTION] → DeadLetterService.handle(rawPayload, reasonCode, ...)
                                    │
                                    ▼
                               H2 (dead_letter_record)

ControlService      → H2 (control_record)
KafkaProducerService → Kafka Output Topic
```

---

## Decisions & Scope

- **H2 is the initial persistence target** for control and dead letter records; both services are
  behind interfaces and fully swappable without touching consumer logic
- **Dead Letter does not re-publish to a Kafka DLQ** at this stage — deferred to later config
- **`MessageProcessorService.process()`** is a stub; business logic filled in separately
- **Offset is never committed on failure** — partition will replay on restart until DLQ skip logic is added
- **Concurrency** via Spring Kafka `concurrency` property only; no additional `@Async` executor
