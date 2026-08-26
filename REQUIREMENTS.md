# Requirements

The functional and non-functional requirements this system was designed against, plus the
assumptions and explicit non-goals that scope it. Each requirement links to the class(es) in
[`CLASS_DIAGRAM.md`](CLASS_DIAGRAM.md) that satisfy it and, where applicable, the endpoint in
[`API.md`](API.md) that exercises it.

## Contents

- [Functional requirements](#functional-requirements)
- [Non-functional requirements](#non-functional-requirements)
- [Assumptions](#assumptions)
- [Explicit non-goals](#explicit-non-goals)

## Functional requirements

| ID | Requirement | Satisfied by | Notes |
|---|---|---|---|
| FR-1 | Accept a notification request for a user and acknowledge it durably. | `NotificationController.create` → `NotificationService.createNotification` ([`POST /notifications`](API.md#1-create-notification)) | Returns `202 Accepted` once persisted — not once delivered. |
| FR-2 | Resolve which channel(s) a notification should be delivered through, based on notification type and the user's stored preference. | `NotificationRouter` / `PreferenceBasedNotificationRouter` | Falls back to `EMAIL` if the user has no preference for that notification type. |
| FR-3 | A single notification may fan out to multiple channels; each channel gets an independent delivery record. | `Notification` *--* `NotificationDelivery` (one row per resolved `Channel`) | See [CLASS_DIAGRAM.md § domain entities](CLASS_DIAGRAM.md). |
| FR-4 | Preferences are evaluated once, at notification-acceptance time; a later preference change must not alter an already-created notification's routing. | `NotificationService.createNotification` resolves routes and creates deliveries in the same transaction | Preferences are per-`(userId, notificationType)`, not global. |
| FR-5 | A delivery must be durably persisted before it is handed off for asynchronous processing. | `NotificationService.createNotification` (persist), `TransactionSynchronization.afterCommit` (publish) | Underpins NFR-1 (at-least-once). |
| FR-6 | Deliver a notification through the channel-appropriate mechanism (email, SMS, push), without the caller knowing which. | `NotificationSender` strategy — `EmailSender`, `SmsSender`, `PushSender` | Every implementation shares one `send(NotificationDelivery)` contract. |
| FR-7 | Apply both a per-user and a per-channel rate limit before a delivery reaches its channel; a dispatch must be allowed by both to proceed. | `RateLimitedSender` + `TokenBucketRateLimiter` | See NFR-4. |
| FR-8 | A rate-limited delivery must not be dropped or counted against its retry budget — it is rescheduled. | `DeliveryDispatchService.process` (catches `RateLimitExceededException` separately from `SendException`) | `attemptCount` is left unchanged; only `nextAttemptAt` moves forward. |
| FR-9 | Retry a failed delivery according to a configurable, per-channel retry policy, distinguishing retryable from non-retryable failures. | `RetryPolicy` / `ExponentialBackoffRetryPolicy` / `RetryPolicyRegistry` | Non-retryable failures (`NonRetryableSendException`) skip retry entirely — see FR-10. |
| FR-10 | A permanently-failing delivery (e.g. invalid recipient) must fail fast rather than exhaust a retry budget. | `NonRetryableSendException` → `DeliveryDispatchService.markFailedPermanently` | Goes straight to `FAILED` + DLQ, no retry attempted. |
| FR-11 | A delivery whose retry budget is exhausted is moved to a dead-letter queue with enough context to diagnose it. | `DlqService.record` → `DeadLetterEntry` | Exposed via [`GET /dlq`](API.md#5-list-dlq-entries). |
| FR-12 | An operator can inspect and replay a dead-lettered delivery without it re-entering through the normal creation flow. | `DlqService.replay` ([`POST /dlq/{deliveryId}/replay`](API.md#6-replay-a-dlq-entry)) | Resets attempt/backoff state and republishes through the same pipeline as a new delivery. |
| FR-13 | A caller can query the current state of a notification and every one of its deliveries at any time. | `NotificationController.get` → `NotificationStatusAggregator` ([`GET /notifications/{notificationId}`](API.md#2-get-notification-status)) | Aggregate status is computed on read, never stored. |
| FR-14 | A caller can set and retrieve a user's channel preference per notification type. | `PreferenceController` / `PreferenceService` ([`PUT`](API.md#3-set-user-preference) / [`GET /users/{userId}/preferences`](API.md#4-get-user-preferences)) | |
| FR-15 | Adding a new channel must not require changes to the orchestrator, dispatcher, retry, or rate-limiting logic. | New `NotificationSender` implementation + one entry in `SenderConfig` + one `Channel` enum value | The extensibility requirement the Strategy/Factory split exists for. |
| FR-16 | Every delivery's lifecycle state (pending, dispatching, retrying, delivered, failed, exhausted) must be individually trackable. | `NotificationDelivery.status` (`DeliveryStatus`) | One channel's failure/retry must not be visible as the others' state. |

## Non-functional requirements

| ID | Requirement | How it's satisfied | Tradeoff / limit |
|---|---|---|---|
| NFR-1 | **At-least-once delivery** — a persisted notification is never silently lost, though duplicate delivery attempts are possible. | Persist-before-publish (FR-5) + `DeliveryReconciliationScheduler` republishes deliveries stuck in `PENDING` (missed publish) or due `RETRYING` (elapsed backoff/cooldown). | Not exactly-once — see [Explicit non-goals](#explicit-non-goals). `deliveryId` is the idempotency key inside this system. |
| NFR-2 | **Extensibility** — new channels, retry policies, and rate-limit algorithms should be addable without touching unrelated code. | `NotificationSender` (Strategy), `NotificationSenderFactory` (Factory), `RetryPolicy` / `RateLimiter` (Strategy) are all interfaces with a single, swappable implementation per concern. | An abstraction only exists where a requirement actually varies (see `README.md` § Design decisions). |
| NFR-3 | **Fault isolation between channels** — one channel being down must not block or delay another channel's delivery. | Each `NotificationDelivery` has its own status/attempt/backoff state, processed independently by `DeliveryDispatchService`. | Demonstrated by `partiallyDelivered_whenOneChannelFailsPermanentlyAndAnotherSucceeds` in the integration test suite. |
| NFR-4 | **Independent rate limiting** — per-user and per-channel limits are enforced separately, and a busy channel must not starve an otherwise-idle user's budget (or vice versa). | `TokenBucketRateLimiter`, one bucket per `RateLimitKey` (`user:<id>` or `channel:<name>`); `RateLimitedSender` short-circuits so a rejected user check never consumes a channel token. | Token bucket chosen over fixed window specifically to avoid boundary-burst double-dispatch — see `README.md`. |
| NFR-5 | **Configurable retry behavior per channel** — different channels/providers should be able to have different retry budgets and backoff curves without code changes. | `RetryPolicyRegistry` builds one `ExponentialBackoffRetryPolicy` per `Channel` from `application.yml` (`notification.retry.channels.*`). | A `multiplier` of `1.0` produces fixed-interval backoff (used for `PUSH` by default) from the same implementation — no separate "fixed backoff" class needed. |
| NFR-6 | **Reasonable, non-blocking latency** — retry backoff must not tie up request-handling or worker threads. | Retry is not a blocking decorator; a retryable failure updates `nextAttemptAt` and returns immediately, and `DeliveryReconciliationScheduler` picks it up later (see `README.md` § Design decisions). | Backoff precision is bounded by `reconciliation-interval-millis` (default 500 ms), not exact to the millisecond. |
| NFR-7 | **Observability** — every dispatch attempt, success, and failure should be logged, and failure reasons should be queryable, without leaking full payload contents into logs. | `LoggingSender` wraps every send attempt; `NotificationDelivery.lastError` / `DeadLetterEntry.failureReason` capture failure detail; payload bodies are never logged. | No metrics/tracing integration is wired up (see non-goals) — logging + the `GET /dlq` and `GET /notifications/{id}` endpoints are the current observability surface. |
| NFR-8 | **Maintainability / separation of concerns** — the orchestrator must stay thin; routing, sending, rate limiting, retry, and DLQ handling are independently testable units. | `NotificationService` only validates → routes → persists → publishes; every other concern lives in its own package (`router`, `sender`, `decorator`, `ratelimit`, `retry`, `dispatch`, `dlq`). | Verified by the unit test suite testing each package in isolation with mocks — no Spring context needed except for the two full-stack integration tests. |
| NFR-9 | **Testability without external dependencies** — the system's behavior (success, permanent failure, retry-then-succeed, retry exhaustion, per-channel mixed outcomes) must be reproducible deterministically from the API alone. | The `simulate` / `simulate.<CHANNEL>` payload keys (see `README.md` § Controlling delivery outcomes for testing) drive every channel sender's outcome instead of a real provider. | This mechanism is scaffolding for this project, not a pattern to carry into a production sender. |
| NFR-10 | **Deployability with minimal infrastructure** — must run and be fully testable with a single command, no external services. | H2 in-memory database; `DeliveryPublisher`'s in-memory queue + worker pool stands in for a broker. | Explicitly scoped to a single JVM instance — see [Explicit non-goals](#explicit-non-goals). |

## Assumptions

These mirror the clarifying questions a real interview would open with (see the original
problem statement) and were resolved as follows for this implementation:

- A notification can fan out to multiple channels (FR-3); this is not an either/or choice.
- Preferences are per `(userId, notificationType)`, not global (FR-2, FR-4).
- A channel being temporarily unavailable is a retryable failure; an invalid recipient/address
  is a permanent one (FR-9, FR-10) — the two are never treated the same way.
- Both a user-level and a channel-level rate limit must independently allow a dispatch (FR-7).
- "At-least-once" permits duplicate delivery attempts; it does not promise exactly-once
  (NFR-1). No end-to-end idempotency key is forwarded to a real provider because none is wired
  up (see non-goals) — `deliveryId` is the idempotency key *within* this system.
- Strict cross-notification ordering is not required. Nothing in the design enforces or relies
  on the order deliveries are processed in.
- Scale numbers were not given, so the design targets a correct, well-separated object/service
  model (an LLD) rather than a specific throughput or a distributed deployment topology.

## Explicit non-goals

Called out up front so they aren't mistaken for oversights:

- **Not exactly-once.** No distributed transaction or provider-side idempotency integration is
  implemented — real providers aren't wired up at all (see below).
- **No real channel providers.** `EmailSender`, `SmsSender`, and `PushSender` don't call any
  external email/SMS/push service; their outcome is controlled entirely by the `simulate`
  payload contract (NFR-9). Swapping in a real provider means replacing the body of one
  `NotificationSender` implementation — nothing else changes.
- **No message broker.** `DeliveryPublisher` is an in-process queue, not Kafka/RabbitMQ/SQS.
  This is sufficient for a single-instance deployment because the source of truth is always the
  database (NFR-1) — but it does not provide durability or delivery across a process restart by
  itself; `DeliveryReconciliationScheduler` is what recovers from that, by re-reading state from
  the database.
- **Not horizontally scaled.** Rate limiting (`TokenBucketRateLimiter`) and the delivery queue
  are in-memory and per-instance. Running multiple instances would need a shared rate-limit
  store (e.g. Redis) and a real broker in place of `DeliveryPublisher` — the `RateLimiter` and
  publish/consume boundary are already interfaces/seams for exactly that swap.
- **No authentication/authorization.** Every endpoint in `API.md` is unauthenticated; this is an
  LLD/demo surface, not a hardened public API.
- **No UI.** The system is API-only, by request — see `API.md` for every testable endpoint.
- **No Docker/infra-as-code.** Runs directly via `./gradlew bootRun` against an in-memory H2
  database, by request.
- **No metrics/tracing backend.** Logging exists (`LoggingSender`) but nothing is wired to
  Prometheus/Grafana/OpenTelemetry/etc.
