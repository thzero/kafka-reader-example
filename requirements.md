# Application Requirements

## Assumptions

### Platform & Build
- Java / Spring Boot application built with Gradle
- Deployable as 10 concurrent instances on a Kafka cluster

### Message Structure
- Every message is a JSON envelope with two top-level properties:
  - **`event`** — header/metadata object containing:
    - `interactionId` — unique identifier for the interaction; used as the correlation ID for all logging
    - `eventType` — the type/category of the event
  - **`body`** — payload object containing:
    - `messageId` — unique identifier for the message; used in control records and dead letter records
- Both `event` and `body` must be deserialized into strongly-typed Java objects
- `interactionId` must be propagated through the entire processing lifecycle (logging, control records, dead letter)

### Throughput & Scalability
- Must sustain a minimum of 1,000,000 messages per day (~12 msg/sec average; must handle burst capacity above this)
- Concurrency and thread pool sizes must be configurable via application properties

### Kafka Reading
- No duplicate messages may be processed; each `messageId` must be handled exactly once
- Deduplication is enforced via **Kafka exactly-once semantics (EOS)**:
  - Consumer is configured with `isolation.level=read_committed` — only reads messages from committed transactions
  - Producer is configured as a **transactional producer** with a unique `transactional-id` per instance
  - The consumer-to-producer flow operates within a single Kafka transaction — the input read and output publish either both commit or both roll back
- If a duplicate `messageId` is detected despite EOS (e.g., a replayed message), it must be routed to the Dead Letter Component with reason code `DUPLICATE`
- Duplicate detection relies on a **unique database constraint** on `ReceivedRecord.messageId`; a `DataIntegrityViolationException` on insert means the message is a duplicate and is routed to the dead letter immediately — this is atomic and race-condition-free
- The duplicate check and insert both happen on the **consumer thread** (before scheduling deferred work), so the consumer itself acts as the gatekeeper
- To replay a failed message: delete its `ReceivedRecord` row, then replay the Kafka message; the constraint insert will succeed and the message will be processed normally

## Capabilities

### Kafka Consumer
- Listens to a configured Kafka input topic
- Uses a shared, common `groupId` across all 10 instances for coordinated partition consumption
- Manual acknowledgment (`enable.auto.commit=false`) — offsets are committed only after successful downstream publish
- Processes messages concurrently (multi-threaded / async processing per message)
- Consumer thread count per instance is configurable via `kafka.consumer.concurrency`
- The correct value is `partitions ÷ instances` — e.g., 10 partitions across 10 instances = `concurrency: 1`; setting it higher wastes idle threads

### Message Processing
- Message payloads are JSON; deserialization must be handled on ingest
- Upon successful deserialization, the message is scheduled for deferred processing
- When the consumer thread receives a message it first attempts to insert a **`ReceivedRecord`** via `ControlService.recordReceived()` — this happens on the consumer thread before scheduling. If the unique constraint fires (`DataIntegrityViolationException`), the message is routed to DUPLICATE dead letter and acknowledged; otherwise work is scheduled for deferred execution
- When the worker thread fires and processing actually begins, processing proceeds — `RECEIVED` represents "processing started"; a `ReceivedRecord` with no matching `PublishedRecord` in reconciliation jobs indicates a genuine processing failure
- Each message is processed independently and in a concurrent fashion
- After successful processing, the message is serialized back to JSON and published to a configured Kafka output topic
- Upon successful publish, the Control Component is invoked to write a `PUBLISHED` control record
- Offset commit to the input topic is performed only after the output publish and control record write complete successfully
- Any failure at any stage (deserialization, processing, publish, or control record write) is handed off to the Dead Letter Component
- Must delay processing by at least 20 seconds (configurable via `app.processing.delay-ms`) to account for upstream race conditions
- The delay is implemented using a `ScheduledExecutorService` — the consumer thread schedules the deferred work and returns immediately, so many messages can be waiting out their delay simultaneously without blocking the consumer
- The number of worker threads in the scheduler is configurable via `app.processing.worker-threads`; size it to handle the maximum number of in-flight messages during the delay window (e.g., 12 msg/sec × 20s = 240 threads)
- This delay is NOT a Kafka setting — Kafka delivers messages instantly; the delay is introduced by our scheduling logic in `KafkaConsumerListener`

### Control Records
- A dedicated **Control Component** is responsible for persisting control records to a database
- Use an in-memory database (e.g., H2) for the initial implementation; the data source must be swappable via configuration
- Control records are stored in **two separate tables** with distinct semantics:
  - **`received_record`** (`ReceivedRecord` entity) — written on the consumer thread immediately after deserialization; has a **unique constraint on `message_id`** which serves as the atomic duplicate guard
    - Fields: `messageId`, `interactionId`, `receivedAt`
  - **`published_record`** (`PublishedRecord` entity) — written after successful publish; no unique constraint
    - Fields: `messageId`, `interactionId`, `publishedAt`
- If an exception occurs at any stage (processing or publishing), the Control Component must **not** write a `PublishedRecord`; the failure is instead routed to the Dead Letter component
- Control Component must be a standalone, injectable service; it must not contain Kafka or processing logic directly

### Error Handling & Dead Letter
- Any exception raised during message processing or during Kafka output publishing is routed to a **Dead Letter Component**
- The Dead Letter Component receives and persists:
  - The original message payload as received from the input topic
  - A `reasonCode` (string/enum) describing the category of failure (e.g., `PROCESSING_ERROR`, `PUBLISH_ERROR`, `DESERIALIZATION_ERROR`)
  - A `messageId` where extractable
  - A `failedAt` timestamp
- Dead Letter storage implementation is pluggable and will be fully configured later; the interface must be defined now
- Failed messages must not block or stall the processing of subsequent messages

### Logging
- All log output must be written to **standard out** (stdout)
- Every log entry must include a **correlation ID** set to the `interactionId` from the message's `event` header
- The correlation ID must be bound to the thread context (e.g., MDC) at the point of message receipt and remain in scope for the full processing lifecycle of that message
- Log entries must be structured (e.g., JSON format) and include at minimum: timestamp, log level, correlation ID (`interactionId`), `messageId` (from body where available), and the log message
- Correlation ID must appear in all log entries related to processing, publishing, control record writes, and dead letter routing
- At a minimum, log at key lifecycle points: message received, processing started, publish succeeded, control record written, dead letter routed, and any exceptions

### Query APIs

#### General Rules
- All APIs are REST, returning JSON responses
- All timestamp parameters are ISO-8601 formatted strings (e.g., `2026-03-30T10:00:00Z`)
- `startTimestamp` defaults to **now minus 12 hours** when not provided
- `endTimestamp` is **optional**; when omitted, the query is open-ended (i.e., up to the most recent record)
- All endpoints must add `spring-boot-starter-web` to the application and be served on a configurable port

#### Control Log — Inbound Records
- `GET /api/control/inbound`
- Returns all `ReceivedRecord` entries within the specified time range
- Query parameters:
  - `startTimestamp` (optional, default: now minus 12 hours) — filters on `receivedAt >= startTimestamp`
  - `endTimestamp` (optional, no default) — filters on `receivedAt <= endTimestamp`
- Response fields per record: `messageId`, `interactionId`, `receivedAt`

#### Control Log — Outbound Records
- `GET /api/control/outbound`
- Returns all `PublishedRecord` entries within the specified time range
- Query parameters:
  - `startTimestamp` (optional, default: now minus 12 hours) — filters on `publishedAt >= startTimestamp`
  - `endTimestamp` (optional, no default) — filters on `publishedAt <= endTimestamp`
- Response fields per record: `messageId`, `interactionId`, `publishedAt`

#### Dead Letter Records
- `GET /api/deadletter`
- Returns all `DeadLetterRecord` entries within the specified time range
- Query parameters:
  - `startTimestamp` (optional, default: now minus 12 hours) — filters on `failedAt >= startTimestamp`
  - `endTimestamp` (optional, no default) — filters on `failedAt <= endTimestamp`
- Response fields per record: `messageId`, `interactionId`, `reasonCode`, `rawPayload`, `failedAt`

## Configuration
- Kafka bootstrap servers, input topic, output topic, `groupId`, and consumer concurrency must all be externally configurable under the `kafka.*` namespace (e.g., `application.yml` / environment variables)
- Application-specific settings that are not part of Kafka must be externalized under the `app.*` namespace to clearly distinguish them from Kafka/Spring internals:
  - `app.processing.delay-ms` — delay before processing each message (default: `20000`)
  - `app.processing.worker-threads` — size of the scheduled executor thread pool per instance (default: `240`)
- API server port must be externally configurable (`server.port`)
- No hardcoded Kafka or environment-specific values in source code

## Non-Functional
- Application must be stateless to support horizontal scaling across 10 instances
- Graceful shutdown must drain in-flight messages before stopping consumers
