package com.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.notifications.dto.DlqEntryResponse;
import com.notifications.dto.NotificationCreatedResponse;
import com.notifications.dto.NotificationStatusResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Covers the failure paths end-to-end through the real HTTP API: permanent failure straight to
 * the DLQ, retry exhaustion into the DLQ, and operator-triggered replay out of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DlqIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @Test
    void permanentFailure_goesStraightToFailedAndDlq_withoutRetrying() {
        String userId = newId("user");
        NotificationCreatedResponse created =
                createNotification(userId, "ORDER_SHIPPED", Map.of("simulate", "PERMANENT_FAILURE"));

        NotificationStatusResponse finalStatus = awaitTerminalStatus(created.notificationId());

        assertThat(finalStatus.status()).isEqualTo("FAILED");
        assertThat(finalStatus.deliveries().get(0).status()).isEqualTo("FAILED");
        assertThat(finalStatus.deliveries().get(0).attemptCount()).isEqualTo(1);

        String deliveryId = finalStatus.deliveries().get(0).deliveryId();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(findDlqEntries(deliveryId)).hasSize(1));
    }

    @Test
    void retryableFailure_exhaustsRetryBudget_thenLandsInDlq() {
        String userId = newId("user");
        NotificationCreatedResponse created =
                createNotification(userId, "ORDER_SHIPPED", Map.of("simulate", "RETRYABLE_FAILURE"));

        NotificationStatusResponse finalStatus = awaitTerminalStatus(created.notificationId());

        assertThat(finalStatus.status()).isEqualTo("FAILED");
        assertThat(finalStatus.deliveries().get(0).status()).isEqualTo("EXHAUSTED");
        // test config caps EMAIL at 3 retries (see src/test/resources/application.yml)
        assertThat(finalStatus.deliveries().get(0).attemptCount()).isEqualTo(3);

        String deliveryId = finalStatus.deliveries().get(0).deliveryId();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(findDlqEntries(deliveryId)).hasSize(1));
    }

    @Test
    void replay_resetsAttemptsAndRedispatches() {
        String userId = newId("user");
        NotificationCreatedResponse created =
                createNotification(userId, "ORDER_SHIPPED", Map.of("simulate", "RETRYABLE_FAILURE"));
        NotificationStatusResponse exhausted = awaitTerminalStatus(created.notificationId());
        String deliveryId = exhausted.deliveries().get(0).deliveryId();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(findDlqEntries(deliveryId)).hasSize(1));

        ResponseEntity<Void> replayResponse =
                restTemplate.exchange(
                        "/dlq/" + deliveryId + "/replay", HttpMethod.POST, null, Void.class);
        assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Same deterministic RETRYABLE_FAILURE payload, so it exhausts again - a second DLQ
        // entry proves the delivery was genuinely reset (attemptCount back to 0) and
        // re-dispatched through the normal pipeline rather than just re-flagged.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(findDlqEntries(deliveryId)).hasSize(2));

        NotificationStatusResponse afterReplay = getStatus(created.notificationId());
        assertThat(afterReplay.deliveries().get(0).attemptCount()).isEqualTo(3);
    }

    private List<DlqEntryResponse> findDlqEntries(String deliveryId) {
        ResponseEntity<DlqEntryResponse[]> response = restTemplate.getForEntity("/dlq", DlqEntryResponse[].class);
        return List.of(response.getBody()).stream()
                .filter(entry -> entry.deliveryId().equals(deliveryId))
                .toList();
    }

    private NotificationCreatedResponse createNotification(String userId, String type, Map<String, String> payload) {
        Map<String, Object> body = Map.of("userId", userId, "type", type, "payload", payload);
        ResponseEntity<NotificationCreatedResponse> response =
                restTemplate.postForEntity("/notifications", body, NotificationCreatedResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody();
    }

    private NotificationStatusResponse getStatus(String notificationId) {
        return restTemplate.getForEntity("/notifications/" + notificationId, NotificationStatusResponse.class)
                .getBody();
    }

    private NotificationStatusResponse awaitTerminalStatus(String notificationId) {
        return await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(
                        () -> getStatus(notificationId),
                        status -> !status.status().equals("IN_PROGRESS") && !status.status().equals("ACCEPTED"));
    }

    private String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
