package com.notifications.sender;

import com.notifications.entity.NotificationDelivery;

/**
 * There are no real email/SMS/push providers wired up in this project (no external accounts,
 * no Docker). Instead every channel sender consults an explicit {@code simulate} key in the
 * delivery payload so behavior is deterministic and fully controllable from the API - this is
 * what makes retry, DLQ, and rate-limit paths reliably testable via curl/integration tests
 * instead of depending on a flaky real provider.
 *
 * <p>Recognized values (case-insensitive), see README for examples:
 * <ul>
 *   <li>absent or {@code SUCCESS} - succeeds immediately</li>
 *   <li>{@code PERMANENT_FAILURE} - always throws a non-retryable failure</li>
 *   <li>{@code RETRYABLE_FAILURE} - always throws a retryable failure (eventually exhausts to DLQ)</li>
 *   <li>{@code FAIL_ONCE} - fails on the first attempt, succeeds from the second attempt onward</li>
 *   <li>{@code FAIL_TWICE} - fails on the first two attempts, succeeds from the third onward</li>
 * </ul>
 *
 * <p>The mode is read from a {@code simulate.<CHANNEL>} payload key first (e.g.
 * {@code simulate.SMS}), falling back to a plain {@code simulate} key that applies to every
 * channel. Per-channel keys are what make it possible to test a genuinely mixed outcome -
 * e.g. EMAIL succeeding while SMS fails - from a single notification request.
 */
final class SimulatedProviderBehavior {

    static final String SIMULATE_KEY = "simulate";

    private SimulatedProviderBehavior() {}

    static void evaluate(NotificationDelivery delivery, String channelLabel) {
        String channelKey = SIMULATE_KEY + "." + delivery.getChannel().name();
        String mode =
                delivery.getPayload()
                        .getOrDefault(channelKey, delivery.getPayload().getOrDefault(SIMULATE_KEY, "SUCCESS"))
                        .toUpperCase();
        // attemptCount is the number of prior provider attempts already made for this delivery
        // (0 the first time a sender is actually invoked) - the dispatcher increments it only
        // after this call returns/throws, so FAIL_ONCE/FAIL_TWICE can key off it directly.
        int priorAttempts = delivery.getAttemptCount();

        switch (mode) {
            case "SUCCESS" -> {
                // fall through to success
            }
            case "PERMANENT_FAILURE" ->
                    throw new NonRetryableSendException(
                            channelLabel + " provider rejected the request: invalid recipient");
            case "RETRYABLE_FAILURE" ->
                    throw new RetryableSendException(channelLabel + " provider temporarily unavailable");
            case "FAIL_ONCE" -> {
                if (priorAttempts < 1) {
                    throw new RetryableSendException(
                            channelLabel + " provider timeout (simulated, priorAttempts=" + priorAttempts + ")");
                }
            }
            case "FAIL_TWICE" -> {
                if (priorAttempts < 2) {
                    throw new RetryableSendException(
                            channelLabel + " provider timeout (simulated, priorAttempts=" + priorAttempts + ")");
                }
            }
            default ->
                    throw new NonRetryableSendException("Unrecognized simulate mode: " + mode);
        }
    }
}
