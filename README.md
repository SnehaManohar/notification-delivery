# Multi-Channel Notification System

A Spring Boot implementation of the classic "multi-channel notification system" LLD interview
problem: accept a notification, resolve delivery channels from user preferences, deliver
asynchronously with retries and rate limiting, and dead-letter anything that can't eventually
be delivered — all with **at-least-once** semantics.

No Docker, no external message broker, no real email/SMS/push provider accounts. Persistence
is an in-memory H2 database and the "message queue" is an in-process worker pool, so the whole
thing runs with a single `./gradlew bootRun` and is fully exercised by the test suite.

## Contents

- [Running it](#running-it)
- [Architecture](#architecture)
- [Design decisions & tradeoffs](#design-decisions--tradeoffs)
- [Controlling delivery outcomes for testing](#controlling-delivery-outcomes-for-testing)
- [API reference](#api-reference)
- [Configuration](#configuration)
- [Project layout](#project-layout)
- [Tests](#tests)

See also: [`REQUIREMENTS.md`](REQUIREMENTS.md) for the functional/non-functional requirements
this system was built against, [`CLASS_DIAGRAM.md`](CLASS_DIAGRAM.md) for a Mermaid diagram of
how every class relates to the others, [`API.md`](API.md) for the full request/response object
reference for every endpoint, and [`DB_SCHEMA.md`](DB_SCHEMA.md) for the database tables and an
ER diagram.

## Running it

Requires JDK 21+ (built and tested on JDK 25) and a network connection the first time (Gradle
downloads dependencies). No other services are needed.

```bash
./gradlew bootRun
```

The API is then available at `http://localhost:8080`. An H2 web console is available at
`http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:notifications`, user `sa`, empty
password) if you want to poke at the tables directly.

Run the full test suite (unit + integration):

```bash
./gradlew test
```

Build a runnable jar:

```bash
./gradlew bootJar
java -jar build/libs/notification-system-0.1.0.jar
```

## Architecture

```text
POST /notifications
        |
        v
NotificationService (orchestrator)
        |
        +--> NotificationRouter -----------> resolves Channel list from UserPreference
        |
        +--> persist Notification + one NotificationDelivery per channel (PENDING)
        |
        +--> after commit: DeliveryPublisher.publish(deliveryId)   [in-memory queue]
                        |
                        v
              DeliveryDispatchService (consumer)
                        |
                        +--> NotificationSenderFactory.getSender(channel)
                        |         |
                        |         v
                        |    LoggingSender -> RateLimitedSender -> {Email,Sms,Push}Sender
                        |
                        +--> on success            -> DELIVERED
                        +--> on rate-limit rejected -> RETRYING (attempt NOT consumed)
                        +--> on retryable failure   -> RETRYING (backoff) or EXHAUSTED -> DLQ
                        +--> on permanent failure    -> FAILED -> DLQ
                        |
              DeliveryReconciliationScheduler
                        |
                        +--> republishes RETRYING deliveries once nextAttemptAt elapses
                        +--> republishes PENDING deliveries stuck longer than a threshold
                             (covers a crash between "persisted" and "published")
```

- **NotificationService** is a thin orchestrator: validate → route → persist → publish. It has
  no idea how email/SMS/push work, and no retry or rate-limit logic.
- **NotificationRouter** owns channel selection (`PreferenceBasedNotificationRouter` reads
  `UserPreference`, falling back to `EMAIL` if the user never configured anything for that
  notification type). Preferences are evaluated once, when the notification is accepted, so a
  later preference change never mutates an already-created notification's routing.
- **NotificationDelivery** is a first-class entity, one per (notification, channel): each
  channel gets its own status/attempt/backoff lifecycle, so one channel retrying doesn't block
  or affect another (fault isolation).
- **NotificationSender** (Strategy) — `EmailSender`, `SmsSender`, `PushSender` all implement the
  same `send(delivery)` contract. Adding WhatsApp means adding `WhatsAppSender` and registering
  it in `SenderConfig` — nothing else changes.
- **NotificationSenderFactory** — a plain `Map<Channel, NotificationSender>` lookup. It exists
  only because the sender implementation is selected at runtime from the channel; no builder or
  factory hierarchy is warranted for that.
- **Decorators** — `LoggingSender` wraps `RateLimitedSender` wraps the raw channel sender.
  Rate limiting sits right next to the raw sender so it governs every actual provider call,
  including retried ones (a provider-facing limit, not a "did we ever try" limit).
- **RetryPolicy** (Strategy, per channel) decides whether a retryable failure gets another
  attempt and how long to back off. See [Design decisions](#design-decisions--tradeoffs) for why
  retry is *not* implemented as a blocking decorator here.
- **DeliveryDispatchService** is the consumer: pulls a sender for the delivery's channel, drives
  one send attempt, and translates the outcome into a state transition.
- **DeliveryPublisher** stands in for a broker: an in-memory queue plus a small worker pool.
- **DeliveryReconciliationScheduler** is the reliable-publishing half of at-least-once: it
  republishes deliveries whose backoff has elapsed, and anything stuck in `PENDING` too long
  (i.e. persisted but never actually published, e.g. after a crash).
- **DlqService** records terminal failures (`FAILED` or `EXHAUSTED`) with enough context to
  diagnose and replay them.

## Design decisions & tradeoffs

- **At-least-once, not exactly-once.** Persistence always happens before publishing (via a
  post-commit `TransactionSynchronization`), so a notification is never lost between "accepted"
  and "queued." Duplicates are possible instead (e.g. a worker crash after the provider
  accepted a message but before the delivery was marked `DELIVERED`) — the `deliveryId` is the
  idempotency key inside this system, and a real provider integration would forward it as an
  idempotency key too.
- **Rate-limit rejection ≠ retry.** `RateLimitedSender` throws a distinct
  `RateLimitExceededException` (not a `SendException`). The dispatcher catches it separately and
  reschedules a short, fixed delay later *without* incrementing `attemptCount` or consuming
  retry budget — being throttled isn't a failed provider attempt.
- **Retry is not a blocking decorator.** The interview script frames retry as a synchronous
  sender decorator with an internal backoff loop. That doesn't fit an asynchronous pipeline well:
  exponential backoff can mean many seconds between attempts, and blocking a worker thread for
  that long defeats the purpose of dispatching asynchronously. Instead, `DeliveryDispatchService`
  consults an injected, channel-specific `RetryPolicy` after a failed attempt, updates
  `nextAttemptAt`, and returns immediately; `DeliveryReconciliationScheduler` republishes the
  delivery once that time elapses. `RetryPolicy` itself is still an independent, swappable
  strategy — only *where* it's consulted differs.
- **Token bucket over fixed window** for rate limiting, because a fixed window can let two full
  bursts land back-to-back at a window boundary (10 requests at `12:00:59` + 10 more at
  `12:01:00` ≈ 20 in under a second). A token bucket smooths that out while still allowing
  controlled bursts up to its capacity. Both a per-user and a per-channel bucket must allow a
  dispatch for it to proceed.
- **One delivery model for all channels**, not per-channel tables — the lifecycle (pending →
  dispatching → retrying/delivered/failed) is identical across channels; only the sender
  implementation and retry/rate-limit configuration vary.
- **Notification type is a free-form string**, not a compiled enum, because routing rules for a
  new notification type are meant to be introduced via `UserPreference` data, not a code change.
- **Notification status is never stored** — `NotificationStatusAggregator` derives it from the
  current state of its deliveries on every read, so the two can't drift out of sync.

## Controlling delivery outcomes for testing

There are no real email/SMS/push providers here, so every channel sender's outcome is
controlled by an explicit `simulate` key in the notification's `payload`. This is what makes
retry, DLQ, and partial-failure paths deterministically testable via curl instead of depending
on a flaky real provider.

| `simulate` value  | Behavior |
|--------------------|----------|
| *(absent)* or `SUCCESS` | Delivers immediately |
| `PERMANENT_FAILURE` | Fails immediately, non-retryable → straight to `FAILED` + DLQ |
| `RETRYABLE_FAILURE` | Always fails with a transient error → retries until the channel's budget is exhausted, then `EXHAUSTED` + DLQ |
| `FAIL_ONCE` | Fails the first attempt, succeeds from the second attempt onward |
| `FAIL_TWICE` | Fails the first two attempts, succeeds from the third onward |

The same payload is shared by every delivery created from one notification, so a plain
`"simulate": "..."` key applies to *all* resolved channels identically. To make one channel
behave differently from another (e.g. to see a real `PARTIALLY_DELIVERED` outcome), use a
per-channel override key: `simulate.EMAIL`, `simulate.SMS`, `simulate.PUSH`. A per-channel key
always takes priority over the plain `simulate` key for that channel.

## API reference

All examples assume the app is running on `http://localhost:8080`.

### Create a notification

```http
POST /notifications
```

```bash
curl -s -X POST http://localhost:8080/notifications \
  -H 'Content-Type: application/json' \
  -d '{
        "userId": "u-123",
        "type": "ORDER_SHIPPED",
        "payload": { "orderId": "O-987" }
      }'
```

```json
{ "notificationId": "bf060794-...", "status": "ACCEPTED" }
```

Delivery is asynchronous — `202 ACCEPTED` only confirms the notification and its resolved
deliveries were durably persisted, not that anything was sent yet. If the user has no stored
preference for `ORDER_SHIPPED`, it defaults to a single `EMAIL` delivery.

Validation: `userId` and `type` are required; a missing field returns `400 Bad Request`.

### Get notification status

```http
GET /notifications/{notificationId}
```

```bash
curl -s http://localhost:8080/notifications/bf060794-...
```

```json
{
  "notificationId": "bf060794-...",
  "userId": "u-123",
  "type": "ORDER_SHIPPED",
  "status": "DELIVERED",
  "deliveries": [
    {
      "deliveryId": "5673a6b1-...",
      "channel": "EMAIL",
      "status": "DELIVERED",
      "attemptCount": 1,
      "lastError": null,
      "nextAttemptAt": null
    }
  ]
}
```

`status` is derived from the deliveries: `ACCEPTED` (no deliveries yet — shouldn't normally be
observed since routing always resolves at least one), `IN_PROGRESS` (any delivery still
pending/dispatching/retrying), `DELIVERED` (all succeeded), `PARTIALLY_DELIVERED` (some
succeeded, some terminally failed), `FAILED` (none succeeded and all are terminal).

Returns `404 Not Found` for an unknown `notificationId`.

### Set channel preferences for a notification type

```http
PUT /users/{userId}/preferences
```

```bash
curl -s -X PUT http://localhost:8080/users/u-123/preferences \
  -H 'Content-Type: application/json' \
  -d '{ "notificationType": "SECURITY_ALERT", "channels": ["EMAIL", "SMS"] }'
```

```json
{ "userId": "u-123", "notificationType": "SECURITY_ALERT", "channels": ["EMAIL", "SMS"] }
```

Valid `channels` values: `EMAIL`, `SMS`, `PUSH`. Calling this again for the same
`notificationType` overwrites the previous selection. Preferences only affect notifications
created *after* they're set.

### Get a user's preferences

```http
GET /users/{userId}/preferences
```

```bash
curl -s http://localhost:8080/users/u-123/preferences
```

```json
[ { "userId": "u-123", "notificationType": "SECURITY_ALERT", "channels": ["EMAIL", "SMS"] } ]
```

### List dead-lettered deliveries

```http
GET /dlq
```

```bash
curl -s http://localhost:8080/dlq
```

```json
[
  {
    "id": 1,
    "deliveryId": "f0df0b43-...",
    "notificationId": "54c3660b-...",
    "userId": "u-123",
    "channel": "EMAIL",
    "failureReason": "Email provider rejected the request: invalid recipient",
    "attemptCount": 1,
    "createdAt": "2026-08-18T16:34:22.257940Z"
  }
]
```

### Replay a dead-lettered delivery

```http
POST /dlq/{deliveryId}/replay
```

```bash
curl -s -i -X POST http://localhost:8080/dlq/f0df0b43-.../replay
```

Resets the delivery's attempt count and backoff state back to `PENDING` and republishes it
through the normal pipeline. Returns `202 Accepted`. The original DLQ entry is left in place as
a historical record — a second entry is added if it fails again (useful for confirming, in a
test, that a replay actually re-ran the pipeline rather than just flipping a flag).

### End-to-end example: retry then DLQ then replay

```bash
# 1. Always-fails delivery, will retry per the channel's configured budget and land in the DLQ
RESP=$(curl -s -X POST http://localhost:8080/notifications \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u-999","type":"ORDER_SHIPPED","payload":{"simulate":"RETRYABLE_FAILURE"}}')
ID=$(echo "$RESP" | grep -oE '"notificationId":"[^"]+"' | cut -d'"' -f4)

# 2. Poll until it's terminal (EMAIL defaults to a few retries with short backoff)
watch -n1 "curl -s http://localhost:8080/notifications/$ID"

# 3. Confirm it's in the DLQ, then replay it
curl -s http://localhost:8080/dlq
curl -s -X POST http://localhost:8080/dlq/<deliveryId>/replay
```

## Configuration

All tunables live in `src/main/resources/application.yml` under the `notification` prefix:

```yaml
notification:
  dispatch:
    worker-count: 4                        # in-memory delivery worker pool size
    reconciliation-interval-millis: 500     # how often the scheduler sweeps for due/stuck deliveries
    rate-limit-retry-delay-millis: 2000     # fixed cool-down after a rate-limit rejection
    stuck-pending-threshold-millis: 5000    # PENDING older than this is republished

  rate-limit:
    user:    { capacity: 20, refill-per-second: 5 }   # per-user token bucket
    channel: { capacity: 50, refill-per-second: 15 }  # per-channel token bucket

  retry:
    default-policy: { max-retries: 3, initial-backoff-millis: 1000, multiplier: 2.0, max-backoff-millis: 10000 }
    channels:
      EMAIL: { max-retries: 5, initial-backoff-millis: 1000, multiplier: 2.0, max-backoff-millis: 10000 }
      SMS:   { max-retries: 3, initial-backoff-millis: 800,  multiplier: 2.0, max-backoff-millis: 8000 }
      PUSH:  { max-retries: 4, initial-backoff-millis: 1000, multiplier: 1.0, max-backoff-millis: 1000 } # multiplier 1.0 = fixed backoff
```

`src/test/resources/application.yml` overrides these with much smaller backoffs/intervals so
the integration test suite runs in seconds instead of minutes.

## Project layout

```text
src/main/java/com/notifications/
  model/        Channel, DeliveryStatus, NotificationStatus, Failure — shared value types
  entity/       Notification, NotificationDelivery, UserPreference, DeadLetterEntry (JPA)
  repository/   Spring Data JPA repositories
  router/       NotificationRouter + PreferenceBasedNotificationRouter
  sender/       NotificationSender strategy, Email/Sms/PushSender, NotificationSenderFactory,
                SendResult/SendException hierarchy, simulated-provider behavior
  decorator/    LoggingSender, RateLimitedSender, RateLimitExceededException
  ratelimit/    RateLimiter, TokenBucketRateLimiter, RateLimitKey
  retry/        RetryPolicy, ExponentialBackoffRetryPolicy, RetryPolicyRegistry
  dispatch/     DeliveryPublisher (in-memory queue), DeliveryDispatchService (consumer),
                DeliveryReconciliationScheduler
  dlq/          DlqService
  service/      NotificationService (orchestrator), PreferenceService, NotificationStatusAggregator
  controller/   NotificationController, PreferenceController, DlqController
  dto/          Request/response records
  config/       SenderConfig (decorator wiring), RateLimiterProperties, RetryProperties
  exception/    GlobalExceptionHandler, ApiError

src/test/java/com/notifications/   mirrors the above for unit tests, plus two full-stack
                                    integration test classes at the root package
```

## Tests

`./gradlew test` runs both layers:

- **Unit tests** (`ratelimit`, `retry`, `router`, `sender`, `decorator`, `dispatch`, `service`
  packages) test each component in isolation with Mockito — no Spring context, fast and
  deterministic. `DeliveryDispatchServiceTest` is the most important one: it verifies every
  state transition (success → `DELIVERED`, permanent failure → `FAILED` + DLQ, retryable
  failure → `RETRYING` or `EXHAUSTED` + DLQ, rate-limited → rescheduled without consuming a
  retry attempt, already-terminal or missing delivery → no-op).
- **Integration tests** (`NotificationApiIntegrationTest`, `DlqIntegrationTest`) boot the full
  Spring context on a random port and drive the real HTTP API with `TestRestTemplate`, using
  [Awaitility](http://github.com/awaitility/awaitility) to poll for the asynchronous pipeline
  (queue → dispatch → retry → scheduler) to reach a terminal state. These cover: default vs.
  preference-based routing, multi-channel fan-out, retry-then-succeed, a genuinely mixed
  `PARTIALLY_DELIVERED` outcome (via per-channel `simulate` overrides), permanent failure to
  DLQ, retry exhaustion to DLQ, DLQ replay, and request validation / 404 handling.
