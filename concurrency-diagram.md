# Message Processing Concurrency Flow

Paste the diagram below into [mermaid.live](https://mermaid.live) to render it.

```mermaid
sequenceDiagram
    participant K as Kafka Broker
    participant CT as Consumer Thread
    participant DB as H2 Database
    participant SE as ScheduledExecutorService
    participant OP as Output Topic

    Note over K,CT: Messages arrive continuously

    K->>CT: Message A (T=0)
    CT->>DB: Deserialize + Duplicate check + Write RECEIVED
    CT->>SE: schedule(processA, delay=20s)
    CT-->>K: returns immediately

    K->>CT: Message B (T=1s)
    CT->>DB: Deserialize + Duplicate check + Write RECEIVED
    CT->>SE: schedule(processB, delay=20s)
    CT-->>K: returns immediately

    K->>CT: Message C (T=2s)
    CT->>DB: Deserialize + Duplicate check + Write RECEIVED
    CT->>SE: schedule(processC, delay=20s)
    CT-->>K: returns immediately

    Note over SE: A, B, C all waiting simultaneously on separate worker threads

    SE->>SE: Message A fires (T=20s) — restore MDC, business logic
    SE->>OP: Publish A
    SE->>DB: Write PUBLISHED (A)
    SE-->>K: Acknowledge offset A

    SE->>SE: Message B fires (T=21s) — restore MDC, business logic
    SE->>OP: Publish B
    SE->>DB: Write PUBLISHED (B)
    SE-->>K: Acknowledge offset B

    SE->>SE: Message C fires (T=22s) — restore MDC, business logic
    SE->>OP: Publish C
    SE->>DB: Write PUBLISHED (C)
    SE-->>K: Acknowledge offset C
```

## Key Points

- The **consumer thread is never blocked** — fast operations only (deserialize, duplicate check, write RECEIVED, schedule), then returns immediately
- **All messages are in-flight simultaneously**, each with their own independent 20-second countdown on a separate worker thread
- The **worker thread pool** is sized to the maximum expected in-flight messages: `msg/sec × delay-ms / 1000` (e.g., 12 msg/sec × 20s = 240 threads)
- **Acknowledgment happens on the worker thread** after the full pipeline completes — Kafka does not advance the offset until then
- If the app restarts mid-flight, un-acked messages are redelivered; the unique constraint on `ReceivedRecord.message_id` detects the duplicate INSERT and routes to dead letter safely
