# API Reference

The request/response contract for every endpoint: fields, types, constraints, status codes, and
the class (from [`CLASS_DIAGRAM.md`](CLASS_DIAGRAM.md)) that produces or consumes each object.
For a narrative walkthrough with curl examples, see [`README.md`](README.md#api-reference).

## Contents

- [1. Create Notification](#1-create-notification)
- [2. Get Notification Status](#2-get-notification-status)
- [3. Set User Preference](#3-set-user-preference)
- [4. Get User Preferences](#4-get-user-preferences)
- [5. List DLQ Entries](#5-list-dlq-entries)
- [6. Replay a DLQ Entry](#6-replay-a-dlq-entry)
- [Shared value types](#shared-value-types)
- [Error response shape](#error-response-shape)

Handled by (in `CLASS_DIAGRAM.md` terms): `NotificationController` and `PreferenceController`
and `DlqController` are the three REST-layer classes; each delegates to a service
(`NotificationService`, `PreferenceService`, `DlqService`) shown wired to them in the diagram.

---

## 1. Create Notification

```
POST /notifications
```

Accepts a logical notification, resolves its channels via `NotificationRouter`, persists one
`NotificationDelivery` per channel, and publishes them for asynchronous processing. Returns as
soon as persistence commits — it does not wait for delivery.

**Handled by:** `NotificationController.create` → `NotificationService.createNotification`

### Request object — `NotificationRequest`

| Field | Type | Required | Notes |
|---|---|---|---|
| `userId` | `string` | Yes (`@NotBlank`) | Opaque user identifier. Used for routing and per-user rate limiting. |
| `type` | `string` | Yes (`@NotBlank`) | Free-form notification type (e.g. `ORDER_SHIPPED`). Matched against `UserPreference.notificationType`. |
| `payload` | `object` (`Map<string, string>`) | No | Arbitrary string key/value pairs, denormalized onto every resulting `NotificationDelivery`. See [README § Controlling delivery outcomes for testing](README.md#controlling-delivery-outcomes-for-testing) for the reserved `simulate` / `simulate.<CHANNEL>` keys used to force success/failure deterministically. |

```json
{
  "userId": "u-123",
  "type": "ORDER_SHIPPED",
  "payload": {
    "orderId": "O-987"
  }
}
```

### Response object — `NotificationCreatedResponse`

**Status:** `202 Accepted`

| Field | Type | Notes |
|---|---|---|
| `notificationId` | `string` | ID of the persisted `Notification` entity. Use this to poll [`GET /notifications/{notificationId}`](#2-get-notification-status). |
| `status` | `string` | Always `"ACCEPTED"` — reflects only that persistence succeeded, not delivery outcome. |

```json
{
  "notificationId": "bf060794-cf23-4809-b947-98cc631064b6",
  "status": "ACCEPTED"
}
```

### Error responses

| Status | Cause |
|---|---|
| `400 Bad Request` | `userId` or `type` missing/blank. Body is an [`ApiError`](#error-response-shape). |

---

## 2. Get Notification Status

```
GET /notifications/{notificationId}
```

Returns the notification and the current state of every delivery fanned out from it.
`status` is computed on every call by `NotificationStatusAggregator` from the current
`NotificationDelivery` rows — it is never stored, so it can't drift from reality.

**Handled by:** `NotificationController.get` → `NotificationService.getNotification` +
`NotificationService.getDeliveries` → `NotificationStatusAggregator.aggregate`

### Request

| Path variable | Type | Notes |
|---|---|---|
| `notificationId` | `string` | ID returned by [Create Notification](#1-create-notification). |

No request body.

### Response object — `NotificationStatusResponse`

**Status:** `200 OK`

| Field | Type | Notes |
|---|---|---|
| `notificationId` | `string` | Echoes the path variable. |
| `userId` | `string` | From the `Notification` entity. |
| `type` | `string` | From the `Notification` entity. |
| `status` | `string` (`NotificationStatus`) | One of `ACCEPTED`, `IN_PROGRESS`, `PARTIALLY_DELIVERED`, `DELIVERED`, `FAILED` — see [Shared value types](#shared-value-types). |
| `deliveries` | `array<DeliveryResponse>` | One entry per resolved channel. |

**`DeliveryResponse` (nested object, one per array element):**

| Field | Type | Notes |
|---|---|---|
| `deliveryId` | `string` | ID of the `NotificationDelivery` row. Used by [Replay a DLQ Entry](#6-replay-a-dlq-entry). |
| `channel` | `string` (`Channel`) | `EMAIL`, `SMS`, or `PUSH`. |
| `status` | `string` (`DeliveryStatus`) | `PENDING`, `DISPATCHING`, `RETRYING`, `DELIVERED`, `FAILED`, or `EXHAUSTED`. |
| `attemptCount` | `int` | Number of real provider attempts made so far (rate-limit rejections don't count). |
| `lastError` | `string` \| `null` | Message from the most recent failure, if any. |
| `nextAttemptAt` | `string` (ISO-8601 instant) \| `null` | When this delivery will next be picked up by `DeliveryReconciliationScheduler`, if it's `RETRYING`. |

```json
{
  "notificationId": "bf060794-cf23-4809-b947-98cc631064b6",
  "userId": "u-123",
  "type": "ORDER_SHIPPED",
  "status": "DELIVERED",
  "deliveries": [
    {
      "deliveryId": "5673a6b1-6ea8-44e1-b91d-b95bc62ffe29",
      "channel": "EMAIL",
      "status": "DELIVERED",
      "attemptCount": 1,
      "lastError": null,
      "nextAttemptAt": null
    }
  ]
}
```

### Error responses

| Status | Cause |
|---|---|
| `404 Not Found` | No `Notification` exists for `notificationId`. Body is an [`ApiError`](#error-response-shape). |

---

## 3. Set User Preference

```
PUT /users/{userId}/preferences
```

Upserts the channel selection for one `(userId, notificationType)` pair, read by
`PreferenceBasedNotificationRouter` the next time a notification of that type is created for
that user. Calling this again for the same type overwrites the previous channel list; it does
not affect notifications already created.

**Handled by:** `PreferenceController.put` → `PreferenceService.setPreference`

### Request

| Path variable | Type | Notes |
|---|---|---|
| `userId` | `string` | Opaque user identifier. |

**Body — `PreferenceRequest`**

| Field | Type | Required | Notes |
|---|---|---|---|
| `notificationType` | `string` | Yes (`@NotBlank`) | Must match the `type` used on [Create Notification](#1-create-notification) requests you want this to govern. |
| `channels` | `array<string>` (`Channel`) | Yes (`@NotEmpty`) | One or more of `EMAIL`, `SMS`, `PUSH`. |

```json
{
  "notificationType": "SECURITY_ALERT",
  "channels": ["EMAIL", "SMS"]
}
```

### Response object — `PreferenceResponse`

**Status:** `200 OK`

| Field | Type | Notes |
|---|---|---|
| `userId` | `string` | Echoes the path variable. |
| `notificationType` | `string` | Echoes the request body. |
| `channels` | `array<string>` | The stored channel set (order not significant — backed by a `Set<Channel>` on `UserPreference`). |

```json
{
  "userId": "u-123",
  "notificationType": "SECURITY_ALERT",
  "channels": ["EMAIL", "SMS"]
}
```

### Error responses

| Status | Cause |
|---|---|
| `400 Bad Request` | `notificationType` blank or `channels` empty/missing/contains a value outside `EMAIL`/`SMS`/`PUSH`. |

---

## 4. Get User Preferences

```
GET /users/{userId}/preferences
```

Lists every `(notificationType, channels)` preference stored for a user.

**Handled by:** `PreferenceController.get` → `PreferenceService.getPreferences`

### Request

| Path variable | Type | Notes |
|---|---|---|
| `userId` | `string` | Opaque user identifier. |

No request body.

### Response object — `array<PreferenceResponse>`

**Status:** `200 OK`

Same `PreferenceResponse` shape as in [§3](#3-set-user-preference), one element per notification
type the user has configured. Empty array if the user has never set a preference (in which case
`PreferenceBasedNotificationRouter` falls back to `EMAIL` for every notification type at
creation time).

```json
[
  { "userId": "u-123", "notificationType": "SECURITY_ALERT", "channels": ["EMAIL", "SMS"] },
  { "userId": "u-123", "notificationType": "MARKETING", "channels": ["PUSH"] }
]
```

### Error responses

None — an unknown `userId` simply returns an empty array.

---

## 5. List DLQ Entries

```
GET /dlq
```

Lists every dead-lettered delivery: a `DeadLetterEntry` is recorded by `DlqService.record`
whenever `DeliveryDispatchService` moves a delivery to `FAILED` (permanent failure) or
`EXHAUSTED` (retry budget spent). One delivery can appear more than once if it was replayed and
failed again — each terminal outcome adds a new entry rather than overwriting the last one.

**Handled by:** `DlqController.list` → `DlqService.listAll`

### Request

No path/query parameters, no body.

### Response object — `array<DlqEntryResponse>`

**Status:** `200 OK`

| Field | Type | Notes |
|---|---|---|
| `id` | `number` | Auto-generated primary key of the `DeadLetterEntry` row. |
| `deliveryId` | `string` | ID of the `NotificationDelivery` that was dead-lettered — pass this to [Replay a DLQ Entry](#6-replay-a-dlq-entry). |
| `notificationId` | `string` | ID of the parent `Notification`. |
| `userId` | `string` | Owning user. |
| `channel` | `string` (`Channel`) | `EMAIL`, `SMS`, or `PUSH`. |
| `failureReason` | `string` | Message from the failure that caused the dead-letter. |
| `attemptCount` | `int` | Attempt count at the moment it was dead-lettered. |
| `createdAt` | `string` (ISO-8601 instant) | When this DLQ entry was recorded. |

```json
[
  {
    "id": 1,
    "deliveryId": "f0df0b43-e91d-4d86-983a-a61b62936b28",
    "notificationId": "54c3660b-70dc-42ec-8e9e-609c04c65444",
    "userId": "u-123",
    "channel": "EMAIL",
    "failureReason": "Email provider rejected the request: invalid recipient",
    "attemptCount": 1,
    "createdAt": "2026-08-18T16:34:22.257940Z"
  }
]
```

### Error responses

None.

---

## 6. Replay a DLQ Entry

```
POST /dlq/{deliveryId}/replay
```

Resets a `NotificationDelivery`'s `status` to `PENDING`, `attemptCount` to `0`, and
`nextAttemptAt`/`lastError` to `null`, then republishes it through
`DeliveryPublisher` → `DeliveryDispatchService` — the same pipeline a brand-new delivery goes
through. The original `DeadLetterEntry` row is left untouched as a historical record; a second
entry is added if the replay fails again.

**Handled by:** `DlqController.replay` → `DlqService.replay`

### Request

| Path variable | Type | Notes |
|---|---|---|
| `deliveryId` | `string` | ID from a `DlqEntryResponse.deliveryId` (see [§5](#5-list-dlq-entries)) or `DeliveryResponse.deliveryId` (see [§2](#2-get-notification-status)). |

No request body.

### Response

**Status:** `202 Accepted`, empty body.

### Error responses

| Status | Cause |
|---|---|
| `404 Not Found` | No `NotificationDelivery` exists for `deliveryId`. Body is an [`ApiError`](#error-response-shape). |

---

## Shared value types

These enums appear as `string` fields across the objects above (see `model` classes in
`CLASS_DIAGRAM.md`):

| Type | Values |
|---|---|
| `Channel` | `EMAIL`, `SMS`, `PUSH` |
| `DeliveryStatus` | `PENDING`, `DISPATCHING`, `RETRYING`, `DELIVERED`, `FAILED`, `EXHAUSTED` |
| `NotificationStatus` | `ACCEPTED`, `IN_PROGRESS`, `PARTIALLY_DELIVERED`, `DELIVERED`, `FAILED` |

## Error response shape

Every non-2xx response from every endpoint above uses the same body, produced by
`GlobalExceptionHandler` — object `ApiError`:

| Field | Type | Notes |
|---|---|---|
| `timestamp` | `string` (ISO-8601 instant) | When the error was generated. |
| `status` | `number` | HTTP status code, e.g. `404`. |
| `error` | `string` | HTTP reason phrase, e.g. `"Not Found"`. |
| `message` | `string` | Human-readable cause. For validation failures, a comma-separated `field: message` list. |

```json
{
  "timestamp": "2026-08-26T10:15:30.123456Z",
  "status": 404,
  "error": "Not Found",
  "message": "No notification found for id bf060794-cf23-4809-b947-98cc631064b6"
}
```
