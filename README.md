# Kafka Message Processor

A Spring Boot application that consumes messages from a Kafka input topic, applies a deliberate processing delay to account for upstream race conditions, and publishes the result to an output topic — with exactly-once semantics, atomic duplicate detection, structured logging, and a REST API for operational visibility.

---

## What It Does

1. **Consumes** JSON messages from a Kafka input topic using `read_committed` isolation (EOS consumer).
2. **Deserializes** each message into a typed `KafkaMessage` envelope (`event` header + `body` payload).
3. **Siphons** Backdated Endorsements (`eventType: END` + `backdated: true`) directly to a dedicated siphon topic and acks immediately — bypassing the delay, duplicate gate, and processing pipeline entirely.
4. **Deduplicates** atomically — uses an in-memory `ConcurrentHashMap` gate on the consumer thread; restart/replay duplicates are caught by a unique constraint on `ReceivedRecord.messageId` on the worker thread.
5. **Schedules** the remaining work on a `ScheduledExecutorService` with a configurable delay (default 20 seconds). The consumer thread returns immediately and is free to pull the next message — no blocking.
6. **Processes** the message via `MessageProcessorService` (business logic stub; swap in your own implementation).
7. **Publishes** the result to a Kafka output topic via a transactional producer.
8. **Acknowledges** the input offset only after the full pipeline succeeds.
9. Routes any failure to the **dead letter** store with a typed `reasonCode`.

---

## Event Types

| Code | Name | Notes |
|------|------|-------|
| `NC`  | New Business | Standard new policy intake |
| `END` | Endorsement | Policy modification. When `event.backdated: true`, this is a **Backdated Endorsement (BDE)** — siphoned directly to `kafka.topic.siphon-bde` on the consumer thread, bypassing the delay, duplicate gate, and processing pipeline. |
| `TRM` | Termination | Policy cancellation/termination |
| `RNW` | Renewal | Policy renewal |

Backdated Endorsements are identified at the field level (`eventType: END` + `backdated: true`) rather than by a distinct event type code. This keeps the routing table simple — `END` always means endorsement; the `backdated` flag drives the siphon fast-path.

---

## Architecture

### Component Overview

```mermaid
flowchart TD
    IT([Input Topic]) -->|consume| CL

    subgraph CL["KafkaConsumerListener — consumer thread (no DB calls)"]
        CL1["1. Deserialize JSON → KafkaMessage"]
        CL2["2. Set MDC (interactionId, messageId)"]
    CL3["3. END+backdated? → siphon + ack"]
        CL4["4. inFlightIds.add(messageId)"]
        CL5["5. Capture MDC snapshot"]
        CL6["6. processingScheduler.schedule(delay=20s)"]
        CL1 --> CL2 --> CL3 --> CL4 --> CL5 --> CL6
    end

    CL3 -->|"END + backdated=true"| ST([Siphon Topic])
    CL3 -->|siphon failure| CL3
    CL4 -->|"already present (in-flight duplicate)"| DL
    CL6 -->|returns immediately| IT

    subgraph WT["Worker Thread — ScheduledExecutorService"]
        WT0["0. Restore MDC from snapshot"]
        WT1["1. ControlService.recordReceived()"]
        WT2["2. MessageProcessorService.process()"]
        WT3["3. KafkaProducerService.publish()"]
        WT4["4. ControlService.recordPublished()"]
        WT5["5. Acknowledgment.acknowledge()"]
        WT6["finally: inFlightIds.remove()"]
        WT0 --> WT1 --> WT2 --> WT3 --> WT4 --> WT5 --> WT6
    end

    CL6 -->|after delay| WT
    WT1 -->|INSERT ok| RR[(received_record\nDB)]
    WT1 -->|"DataIntegrityViolationException\n(restart/replay duplicate)"| DL
    WT3 -->|publish transactional| OT([Output Topic])
    WT4 --> PR[(published_record\nDB)]
    WT2 -->|ProcessingException| DL
    WT3 -->|KafkaPublishException| DL
    WT4 -->|Exception| DL

    DL["DeadLetterService"]
    DL --> DLR[(dead_letter_record\nDB)]
```

### Concurrency Flow

```mermaid
sequenceDiagram
    participant K as Kafka Broker
    participant CT as Consumer Thread
    participant DB as H2 Database
    participant SE as ScheduledExecutorService
    participant OP as Output Topic

    Note over K,CT: Messages arrive continuously

    K->>CT: Message A (T=0)
    CT->>CT: Deserialize + in-memory duplicate check (ConcurrentHashMap)
    CT->>SE: schedule(processA, delay=20s)
    CT-->>K: returns immediately

    K->>CT: Message B (T=1s)
    CT->>CT: Deserialize + in-memory duplicate check (ConcurrentHashMap)
    CT->>SE: schedule(processB, delay=20s)
    CT-->>K: returns immediately

    K->>CT: Message C (T=2s)
    CT->>CT: Deserialize + in-memory duplicate check (ConcurrentHashMap)
    CT->>SE: schedule(processC, delay=20s)
    CT-->>K: returns immediately

    Note over SE: A, B, C all waiting simultaneously on separate worker threads

    SE->>SE: Message A fires (T=20s) — restore MDC
    SE->>DB: Write RECEIVED (A)
    SE->>SE: Business logic
    SE->>OP: Publish A
    SE->>DB: Write PUBLISHED (A)
    SE-->>K: Acknowledge offset A

    SE->>SE: Message B fires (T=21s) — restore MDC
    SE->>DB: Write RECEIVED (B)
    SE->>SE: Business logic
    SE->>OP: Publish B
    SE->>DB: Write PUBLISHED (B)
    SE-->>K: Acknowledge offset B

    SE->>SE: Message C fires (T=22s) — restore MDC
    SE->>DB: Write RECEIVED (C)
    SE->>SE: Business logic
    SE->>OP: Publish C
    SE->>DB: Write PUBLISHED (C)
    SE-->>K: Acknowledge offset C
```

**Key points:**
- The **consumer thread makes zero DB calls** — fast operations only (deserialize, BDE siphon check, nanosecond in-memory duplicate check, schedule), then returns immediately

---

## Siphon Routing

The siphon system fast-paths selected messages directly to dedicated Kafka topics on the consumer thread, bypassing the 20-second delay, duplicate gate, and processing pipeline entirely.

### How it works

`KafkaConsumerListener` iterates a `List<SiphonEvaluator>` (first match wins). For each evaluator, `evaluate(message)` returns:
- `Optional.of(topicName)` — siphon to that topic and ack immediately, stopping evaluation
- `Optional.empty()` — continue to the next evaluator; if none match, the message follows the normal pipeline

Active evaluators are controlled by `app.siphon.enabled` (list of event codes). An empty list activates all registered evaluators.

### Naming convention

| Thing | Pattern | Example |
|-------|---------|--------|
| Evaluator class | `{EventCode}SiphonEvaluator` | `BdeSiphonEvaluator` |
| YAML property | `kafka.topic.siphon-{event-code}` | `kafka.topic.siphon-bde` |
| `@Value` annotation | `${kafka.topic.siphon-{event-code}}` | `${kafka.topic.siphon-bde}` |

### Implemented evaluators

| Class | Event code | Matches | Topic property |
|-------|-----------|---------|----------------|
| `BdeSiphonEvaluator` | `bde` | `eventType=END` + `backdated=true` | `kafka.topic.siphon-bde` |

Control which are active via `app.siphon.enabled` (empty = all active).

### Adding a new siphon route

1. Implement `SiphonEvaluator`, inject your topic via `@Value`, annotate with `@Component`:
   ```java
   @Component
   public class TrmSiphonEvaluator implements SiphonEvaluator {
       private final String topic;
       public TrmSiphonEvaluator(@Value("${kafka.topic.siphon-trm}") String topic) {
           this.topic = topic;
       }
       @Override
       public String eventCode() { return "trm"; }
       @Override
       public Optional<String> evaluate(KafkaMessage message) {
           if (message.event() == null) return Optional.empty();
           return EventType.TRM.equals(message.event().eventType())
               ? Optional.of(topic) : Optional.empty();
       }
   }
   ```

2. Add the topic key to `application.yml`:
   ```yaml
   kafka:
     topic:
       siphon-trm: trm-siphon-topic
   ```

3. Add the event code to `app.siphon.enabled`:
   ```yaml
   app:
     siphon:
       enabled: [bde, trm]
   ```
- **All messages are in-flight simultaneously**, each with their own independent 20-second countdown on a separate worker thread
- The **worker thread pool** is sized to the maximum expected in-flight messages: `msg/sec × delay-ms / 1000` (e.g., 12 msg/sec × 20s = 240 threads)
- **Acknowledgment happens on the worker thread** after the full pipeline completes — Kafka does not advance the offset until then
- If the app restarts mid-flight, un-acked messages are redelivered; the unique constraint on `ReceivedRecord.message_id` catches restart/replay duplicates on the worker thread

---

## Event Processing

After the siphon check, the normal pipeline delegates business logic to an `EventProcessor` implementation selected by `eventType`. This lets each event code have its own processing strategy without changing the listener or pipeline.

### How it works

`MessageProcessorService` is injected with all `@Component` beans that implement `EventProcessor`. At startup it builds a `Map<eventCode, EventProcessor>`. For each message, `process(message, rawPayload)` looks up the processor by `message.event().eventType()` and falls back to the `"*"` default processor when no specific handler is registered.

The processor owns the publish step — it calls `KafkaProducerService.publish(key, payload, topic)` directly. Throwing any exception routes the message to dead letter:
- `KafkaPublishException` → `PUBLISH_ERROR`
- Any other exception → `PROCESSING_ERROR`

### Implemented processors

| Class | Event code | Behaviour |
|-------|-----------|----------|
| `DefaultEventProcessor` | `*` (fallback) | Serializes the `KafkaMessage` to JSON and publishes to `kafka.topic.output` |

### Adding a new processor

1. Implement `EventProcessor`, annotate with `@Component`:
   ```java
   @Component
   public class RenewalEventProcessor implements EventProcessor {
       private final KafkaProducerService publisher;
       private final String outputTopic;

       public RenewalEventProcessor(
               KafkaProducerService publisher,
               @Value("${kafka.topic.output}") String outputTopic) {
           this.publisher = publisher;
           this.outputTopic = outputTopic;
       }

       @Override
       public String eventCode() { return "RNW"; }

       @Override
       public void process(KafkaMessage message, String rawPayload) {
           // custom renewal logic here
           String key = message.body() != null ? message.body().messageId() : null;
           publisher.publish(key, rawPayload, outputTopic);
       }
   }
   ```

2. That's it — `MessageProcessorService` picks up the new bean automatically via Spring's `List<EventProcessor>` injection. No other changes required.

> **Note:** `eventCode()` must match the `eventType` string in the incoming message exactly (e.g. `"RNW"`). The `"*"` code is reserved for the default fallback — do not use it in a concrete processor.

---

## Duplicate Detection

Duplicate detection uses two layers so the consumer thread never touches the database:

**Layer 1 — In-memory gate (consumer thread, nanosecond cost):**
- A `ConcurrentHashMap.newKeySet()` holds all `messageId`s currently in-flight.
- `add()` returns `false` if already present — the message is a duplicate within the 20-second delay window.
- Route to dead letter with `DUPLICATE` and ack immediately. No DB call, no round-trip.
- The set entry is removed in the worker's `finally` block (on success and on failure), so redelivered messages can re-enter the pipeline.

**Layer 2 — DB unique constraint (worker thread, restart/replay safety):**
- `ReceivedRecord` has a unique constraint on `message_id`.
- On app restart, the in-memory set is empty. When an un-acked message is redelivered, it passes Layer 1 but the DB constraint fires a `DataIntegrityViolationException` on the worker thread.
- Route to dead letter with `DUPLICATE` and ack.

**To replay a failed message:** delete its row from `received_record`, then replay the Kafka message. The INSERT will succeed and the message will process normally.

---

## Dead Letter Reason Codes

| Code | Trigger |
|------|---------|
| `DESERIALIZATION_ERROR` | Message payload is not valid JSON or does not match the expected schema |
| `INVALID_MESSAGE_ID` | `body.messageId` is missing or is not a valid UUID (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) |
| `DUPLICATE` | `messageId` already in the in-flight set (same-instance duplicate during delay window), or `DataIntegrityViolationException` from the DB unique constraint (restart/replay duplicate) |
| `CONTROL_RECORD_ERROR` | Unexpected failure writing the `ReceivedRecord` (not a constraint violation) |
| `PROCESSING_ERROR` | `MessageProcessorService.process()` threw an exception |
| `PUBLISH_ERROR` | `KafkaProducerService.publish()` threw an exception |

---

## REST API

All endpoints return JSON. Timestamps are ISO-8601. `startTimestamp` defaults to **now minus 12 hours** when omitted; `endTimestamp` is optional and open-ended when omitted.

### `GET /api/control/inbound`
Returns `ReceivedRecord` entries — messages that entered the processing pipeline.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `startTimestamp` | now − 12h | Filter: `receivedAt >=` |
| `endTimestamp` | none | Filter: `receivedAt <=` |

Response fields: `messageId`, `interactionId`, `receivedAt`

### `GET /api/control/outbound`
Returns `PublishedRecord` entries — messages successfully published to the output topic.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `startTimestamp` | now − 12h | Filter: `publishedAt >=` |
| `endTimestamp` | none | Filter: `publishedAt <=` |

Response fields: `messageId`, `interactionId`, `publishedAt`

> A `messageId` that appears in inbound but not outbound within a reasonable time window indicates a processing failure worth investigating.

### `GET /api/deadletter`
Returns dead letter entries — messages that failed at any stage.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `startTimestamp` | now − 12h | Filter: `failedAt >=` |
| `endTimestamp` | none | Filter: `failedAt <=` |

Response fields: `messageId`, `interactionId`, `reasonCode`, `rawPayload`, `failedAt`

### `GET /api/config`
Returns the current running configuration as JSON.

Response fields: `kafka.bootstrapServers`, `kafka.consumerGroupId`, `kafka.consumerConcurrency`, `kafka.inputTopic`, `kafka.outputTopic`, `app.processingDelayMs`, `app.processingWorkerThreads`, `app.siphonEnabledEvaluators`

---

## Configuration

All settings are in `src/main/resources/application.yml`.

```yaml
app:
  processing:
    delay-ms: 20000       # ms to wait before processing each message (NOT a Kafka setting)
    worker-threads: 240   # thread pool size = msg/sec × delay-ms / 1000
  siphon:
    enabled: [bde]        # event codes of active SiphonEvaluators (empty = all active)

kafka:
  bootstrap-servers: localhost:9092
  consumer:
    group-id: kafka-processor-group
    concurrency: 1        # threads per instance = partitions ÷ instances (10 ÷ 10 = 1)
  producer:
    transactional-id-prefix: kafkaprocessor-tx   # unique prefix per instance; Spring appends a sequence number
  topic:
    input: input-topic
    output: output-topic
    siphon-bde: siphon-bde-topic    # BdeSiphonEvaluator — Backdated Endorsements (END + backdated=true)
    # siphon-trm: trm-topic         # example: add TrmSiphonEvaluator for TRM events

server:
  port: 8080
```

**Sizing `worker-threads`:** multiply your expected messages/sec by your `delay-ms` in seconds. At 12 msg/sec with a 20-second delay, up to 240 messages can be simultaneously in-flight.

**Sizing `concurrency`:** set to `total partitions ÷ deployed instances`. With 10 partitions across 10 instances, `concurrency: 1` gives each instance exactly one partition. Setting it higher creates idle threads.

**`transactional-id-prefix`:** must be unique per deployed instance. Spring appends a monotonically increasing sequence number to form the full transactional ID. In multi-instance deployments, include an instance identifier in the prefix (e.g. `kafkaprocessor-tx-${INSTANCE_ID}`).

**Database:** the default config uses an H2 in-memory database which is wiped on every restart. For production, replace the `spring.datasource.*` and `spring.jpa.*` blocks with your target database (e.g. PostgreSQL, Oracle, SQL Server) and set `ddl-auto: validate` or `none`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/kafkaprocessor
    username: kafkaprocessor
    password: secret
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate   # never use create-drop in production
    show-sql: false
```

---

## Project Structure

```
src/main/java/com/example/kafkaprocessor/
├── KafkaProcessorApplication.java       # entry point
├── api/
│   ├── ConfigController.java            # GET /api/config — configuration view
│   ├── QueryController.java             # REST query endpoints
│   └── TimeRangeHelper.java             # default timestamp resolution
├── config/
│   └── AppProperties.java               # @ConfigurationProperties for app.*
├── control/
│   ├── ControlService.java              # interface
│   ├── ControlServiceImpl.java          # JPA implementation
│   ├── ReceivedRecord.java              # entity — unique constraint on messageId
│   ├── ReceivedRecordRepository.java
│   ├── PublishedRecord.java             # entity — no unique constraint
│   └── PublishedRecordRepository.java
├── deadletter/
│   ├── DeadLetterService.java           # interface
│   ├── DeadLetterServiceImpl.java       # JPA implementation
│   ├── DeadLetterRecord.java            # entity
│   ├── DeadLetterRepository.java
│   └── ReasonCode.java                  # enum
├── kafka/
│   ├── KafkaConsumerConfig.java         # consumer + scheduler + active evaluator beans
│   ├── KafkaConsumerListener.java       # @KafkaListener — main pipeline
│   ├── KafkaProducerConfig.java         # transactional producer bean
│   ├── KafkaProducerService.java        # publish(key, payload, topic) — used by siphon and processors
│   ├── KafkaPublishException.java
│   ├── MessageProcessorService.java     # routes by eventType to registered EventProcessor beans
│   ├── ProcessingException.java
│   ├── processor/
│   │   ├── EventProcessor.java          # interface — eventCode() + process(KafkaMessage, rawPayload)
│   │   └── DefaultEventProcessor.java  # fallback for unrecognised event types (eventCode = "*")
│   └── siphon/
│       ├── SiphonEvaluator.java         # interface — eventCode() + evaluate()
│       └── BdeSiphonEvaluator.java      # routes END+backdated=true to siphon-bde topic
├── logging/
│   └── MdcContext.java                  # MDC set/clear helpers
└── model/
    ├── EventHeader.java                 # record — interactionId, eventType
    ├── KafkaMessage.java                # record — event + body envelope
    └── MessageBody.java                 # record — messageId

src/test/java/com/example/kafkaprocessor/
├── api/
│   ├── ConfigControllerTest.java        # MVC slice test
│   └── QueryControllerTest.java         # MVC slice test
├── control/ControlServiceImplTest.java  # JPA slice test
├── deadletter/DeadLetterServiceImplTest.java
├── integration/KafkaIntegrationTest.java # @EmbeddedKafka full test
└── kafka/
    ├── KafkaConsumerListenerTest.java    # unit test
    └── siphon/
        └── BdeSiphonEvaluatorTest.java   # unit test
```

---

## Running Locally

Requires Java 21 and Docker Desktop. All Gradle commands use `gradlew` (the wrapper) — no local Gradle installation needed.

### 1. Start the local stack

```bash
# First run or after changing KAFKA_CLUSTER_ID — clear stale volumes first:
docker compose down -v

# Start all services in the background:
docker compose up -d

# Open Grafana once the stack is up:
start http://localhost:3000
```

This starts four services:

| Service | URL | Credentials |
|---------|-----|-------------|
| Kafka broker (host) | `localhost:9092` | — |
| Kafka broker (internal) | `kafka:29092` | — (Docker container-to-container only) |
| Kafka UI | http://localhost:8081 | — |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | admin / admin |

> **Kafka listener split:** Two advertised listeners are configured — `PLAINTEXT://localhost:9092` for the Spring Boot app running on the host, and `INTERNAL://kafka:29092` for services inside Docker (Kafka UI, etc.). Kafka tells connecting clients to reconnect on the advertised address, so a container given `localhost:9092` would try to connect to itself. Using the Docker service name `kafka:29092` resolves correctly within the Docker network.

**Kafka UI** (`http://localhost:8081`) — browse topics, consumer group lag, and individual messages.

**Prometheus** (`http://localhost:9090`) — raw metric store. Scrapes `/actuator/prometheus` on the running app every 5 seconds. Use the **Graph** tab to run ad-hoc PromQL queries.

**Grafana** (`http://localhost:3000`) — dashboards and alerting. Prometheus is auto-provisioned as the default datasource on first start — no manual setup needed.

#### Using Grafana

1. Open http://localhost:3000 and log in with `admin` / `admin`
2. Go to **Explore** (compass icon in the left sidebar) — the Prometheus datasource is pre-selected
3. Enter a PromQL query and click **Run query**

Useful queries:

```promql
# P95 end-to-end latency over the last minute (includes processing delay)
histogram_quantile(0.95, rate(kafka_processor_e2e_latency_seconds_bucket[1m]))

# P95 pipeline execution latency (code only, excludes delay)
histogram_quantile(0.95, rate(kafka_processor_pipeline_latency_seconds_bucket[1m]))

# Message throughput per second (published)
rate(kafka_processor_messages_published_total[1m])

# Dead letter rate by reason code
rate(kafka_processor_messages_failed_total[1m])

# Current in-flight estimate
kafka_processor_messages_received_total - kafka_processor_messages_published_total - kafka_processor_messages_failed_total
```

**CPU & Memory** (auto-collected by Micrometer — no code changes needed):

```promql
# JVM process CPU usage (0–1, multiply by 100 for %)
process_cpu_usage * 100

# System-wide CPU usage across all cores (0–1)
system_cpu_usage * 100

# JVM heap used vs max
jvm_memory_used_bytes{area="heap"}
jvm_memory_max_bytes{area="heap"}

# Heap utilization %
sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}) * 100

# Non-heap (Metaspace, code cache, etc.)
jvm_memory_used_bytes{area="nonheap"}

# GC pause P95 latency
histogram_quantile(0.95, rate(jvm_gc_pause_seconds_bucket[1m]))

# GC pause rate (how often GC is running)
rate(jvm_gc_pause_seconds_count[1m])

# Live threads
jvm_threads_live_threads
jvm_threads_daemon_threads
```

To build a dashboard: **Dashboards → New → Add visualization**, paste a query, and save.

#### Stopping the stack

```bash
docker compose down        # stop containers, keep volumes
docker compose down -v     # stop containers AND delete all data
```

#### Resetting topics

Use this to clear messages from a topic between test runs without restarting the whole stack.

**Delete a topic** (auto-recreated on next produce/consume since `auto.create.topics.enable=true`):
```powershell
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic input-topic
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic output-topic
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic siphon-bde-topic
```

**Reset consumer group offset to beginning** (re-read all existing messages without deleting them — app must be stopped first):
```powershell
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh `
  --bootstrap-server localhost:9092 `
  --group kafka-processor-group `
  --topic input-topic `
  --reset-offsets --to-earliest --execute
```

### 2. Build and run all tests

```bash
.\gradlew test
```

### 3. Run the application

Two independent profile axes control behaviour at startup:

| Axis | Profile | Effect |
|------|---------|--------|
| **Metrics** | `prometheus` *(default)* | `/actuator/prometheus` scraped by local Docker Prometheus |
| **Metrics** | `datadog` | Pushes metrics to Datadog every 10s — requires `DD_API_KEY` |
| **Logging** | `local` *(default)* | Human-readable log lines (`HH:mm:ss LEVEL logger interactionId messageId message`) |
| **Logging** | *(omit `local`)* | Structured JSON via logstash-logback-encoder — for CI / production |

**Local dev (readable logs + Prometheus — default):**
```powershell
.\gradlew bootRun
```

**Local dev with JSON logs (e.g. testing log aggregation):**
```powershell
.\gradlew bootRun --args='--spring.profiles.active=prometheus'
```

**Work (Datadog, JSON logs):**
```powershell
$env:DD_API_KEY = "your-api-key"
$env:SPRING_PROFILES_ACTIVE = "datadog"
.\gradlew bootRun
```
Or as a one-liner:
```bash
DD_API_KEY=your-api-key ./gradlew bootRun --args='--spring.profiles.active=datadog'
```

The `kafka.processor.*` counters and timers appear in Datadog automatically under those metric names. The `/actuator/prometheus` endpoint is only available under the `prometheus` profile.

### 4. Generate test messages

```powershell
# Default: 1000 messages with default distribution
.\gradlew generateMessages

# Custom count
.\gradlew generateMessages -Pcount=200

# Custom distribution (must sum to 100)
.\gradlew generateMessages -Pcount=500 -PpctNC=30 -PpctEND=45 -PpctTRM=5 -PpctRNW=20

# Custom BDE ratio within END events (default 20%)
.\gradlew generateMessages -Pcount=100 -PpctBDE=40
```

Output is written to `build/generated-messages/messages-<count>.jsonl` — one JSON object per line.

**Windows shortcut — `gen-messages.cmd`:**

```bat
gen-messages.cmd                         :: 1000 messages, default distribution
gen-messages.cmd 500                     :: 500 messages, default distribution
gen-messages.cmd 200 10 60 10 20 15      :: count pctNC pctEND pctTRM pctRNW pctBDE
```

### 5. Send messages to Kafka

```bat
send-messages.cmd                                          :: sends most recent JSONL → input-topic
send-messages.cmd build\generated-messages\messages-100.jsonl
send-messages.cmd build\generated-messages\messages-100.jsonl my-input-topic
```

Requires the `kafka` container to be running. The script copies the JSONL into the container and pipes it through `kafka-console-producer`.

### 6. Monitor pipeline timings

Open Grafana at **http://localhost:3000** → Dashboards → **Kafka Processor**.

The provisioned dashboard auto-loads and shows:
- **Throughput**: message rate and in-flight estimate
- **Latency**: E2E and pipeline P50 / P95 / P99
- **Dead letters**: rate by reason code
- **CPU & Memory**: process CPU %, JVM heap, threads, GC pause

### Full test loop

```powershell
# 1. Start the Docker stack (Kafka, Kafka UI, Prometheus, Grafana) — skip if already running
docker compose up -d

# 2. Start the application (new terminal)
.\gradlew bootRun

# 3. Generate test messages
gen-messages.cmd 100

# 4. Send them to Kafka
send-messages.cmd

# 5. Watch the pipeline process them in Grafana
start http://localhost:3000
```

While messages are processing, check the UIs:

| URL | What to look for |
|-----|-----------------|
| http://localhost:8081 | Kafka UI — consumer group lag draining on `input-topic`; messages appearing on `output-topic` and `siphon-bde-topic` |
| http://localhost:8080/actuator/health | App health — `processorThreadPool` utilization |
| http://localhost:3000 | Grafana — E2E and pipeline latency histograms (Explore tab) |

Arguments are positional and all optional — only the ones provided are passed to Gradle.

---

## Bruno API Collection

A [Bruno](https://www.usebruno.com/) collection is included in the `bruno/` folder, covering all REST endpoints and Actuator health/metrics checks.

**Open the collection:**
1. Install Bruno (free, open-source — [usebruno.com](https://www.usebruno.com/))
2. In Bruno: **Open Collection** → select the `bruno/` folder
3. Select the **local** environment (top-right dropdown) — sets `baseUrl` to `http://localhost:8080`

**Requests included:**

| Folder | Request | Endpoint |
|--------|---------|----------|
| api | Get Config | `GET /api/config` |
| api | Get Control Inbound | `GET /api/control/inbound` |
| api | Get Control Outbound | `GET /api/control/outbound` |
| api | Get Dead Letter | `GET /api/deadletter` |
| actuator | Health | `GET /actuator/health` |
| actuator | Health - Processor Thread Pool | `GET /actuator/health/processorThreadPool` |
| actuator | Metrics | `GET /actuator/metrics` |
| actuator | Metrics - E2E Latency | `GET /actuator/metrics/kafka.processor.e2e.latency` |
| actuator | Metrics - Pipeline Latency | `GET /actuator/metrics/kafka.processor.pipeline.latency` |
| actuator | Metrics - Messages Received | `GET /actuator/metrics/kafka.processor.messages.received` |
| actuator | Metrics - Messages Published | `GET /actuator/metrics/kafka.processor.messages.published` |
| actuator | Metrics - Messages Siphoned | `GET /actuator/metrics/kafka.processor.messages.siphoned` |
| actuator | Metrics - Messages Failed | `GET /actuator/metrics/kafka.processor.messages.failed` |
| actuator | Prometheus Scrape | `GET /actuator/prometheus` |
| actuator | Info | `GET /actuator/info` |

> Optional query parameters (`startTimestamp`, `endTimestamp`, `tag`) are pre-filled but **disabled** by default (prefixed with `~` in the `.bru` files). Enable them in Bruno's Params tab when needed.

---

## Documentation

| File | Contents |
|------|----------|
| [requirements.md](requirements.md) | Full functional and non-functional requirements |
| [plan.md](plan.md) | Implementation phases and component breakdown |
| [tests.md](tests.md) | Description of every test and which requirement it covers |
| [concurrency-diagram.md](concurrency-diagram.md) | Mermaid sequence diagram of the scheduling model |
| [why.md](why.md) | Rationale for key architectural decisions |
