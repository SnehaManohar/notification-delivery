package com.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.notifications.dto.DeliveryResponse;
import com.notifications.dto.NotificationCreatedResponse;
import com.notifications.dto.NotificationStatusResponse;
import com.notifications.dto.PreferenceRequest;
import com.notifications.dto.PreferenceResponse;
import com.notifications.model.Channel;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end tests driven purely through the HTTP API - the same requests documented in the
 * README. Each test uses a freshly generated userId/notificationType so tests don't interfere
 * with each other even though they share one Spring context (and therefore one in-memory DB).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationApiIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @Test
    void createNotification_withNoPreference_deliversToDefaultEmailChannel() {
        String userId = newId("user");
        String type = "ORDER_SHIPPED";

        NotificationCreatedResponse created = createNotification(userId, type, Map.of());
        assertThat(created.status()).isEqualTo("ACCEPTED");

        NotificationStatusResponse finalStatus = awaitTerminalStatus(created.notificationId());

        assertThat(finalStatus.status()).isEqualTo("DELIVERED");
        assertThat(finalStatus.deliveries()).hasSize(1);
        assertThat(finalStatus.deliveries().get(0).channel()).isEqualTo("EMAIL");
        assertThat(finalStatus.deliveries().get(0).status()).isEqualTo("DELIVERED");
    }

    @Test
    void createNotification_routesToAllPreferredChannels() {
        String userId = newId("user");
        String type = "SECURITY_ALERT";

        setPreference(userId, type, Channel.EMAIL, Channel.SMS, Channel.PUSH);

        NotificationCreatedResponse created = createNotification(userId, type, Map.of());
        NotificationStatusResponse finalStatus = awaitTerminalStatus(created.notificationId());

        assertThat(finalStatus.status()).isEqualTo("DELIVERED");
        assertThat(finalStatus.deliveries()).hasSize(3);
        assertThat(finalStatus.deliveries().stream().map(DeliveryResponse::channel))
                .containsExactlyInAnyOrder("EMAIL", "SMS", "PUSH");
    }

    @Test
    void createNotification_routesOnlyToPreferredChannel() {
        String userId = newId("user");
        String type = "MARKETING";

        setPreference(userId, type, Channel.PUSH);

        NotificationCreatedResponse created = createNotification(userId, type, Map.of());
        NotificationStatusResponse finalStatus = awaitTerminalStatus(created.notificationId());

        assertThat(finalStatus.deliveries()).hasSize(1);
        assertThat(finalStatus.deliveries().get(0).channel()).isEqualTo("PUSH");
    }

    @Test
    void retryableFailureThatClearsWithinBudget_eventuallyDelivers() {
        String userId = newId("user");
        String type = "PASSWORD_RESET";

        NotificationCreatedResponse created = createNotification(userId, type, Map.of("simulate", "FAIL_ONCE"));
        NotificationStatusResponse finalStatus = awaitTerminalStatus(created.notificationId());

        assertThat(finalStatus.status()).isEqualTo("DELIVERED");
        assertThat(finalStatus.deliveries().get(0).attemptCount()).isEqualTo(2);
    }

    @Test
    void partiallyDelivered_whenOneChannelFailsPermanentlyAndAnotherSucceeds() {
        String userId = newId("user");
        String type = "ORDER_SHIPPED";
        setPreference(userId, type, Channel.EMAIL, Channel.SMS);

        // A per-channel simulate override (simulate.SMS) lets one channel fail permanently
        // while EMAIL, governed only by the generic fallback, succeeds - demonstrating that
        // each channel's delivery lifecycle is genuinely independent (fault isolation).
        NotificationCreatedResponse created =
                createNotification(userId, type, Map.of("simulate.SMS", "PERMANENT_FAILURE"));
        NotificationStatusResponse finalStatus = awaitTerminalStatus(created.notificationId());

        assertThat(finalStatus.status()).isEqualTo("PARTIALLY_DELIVERED");
        assertThat(finalStatus.deliveries()).hasSize(2);

        Map<String, String> statusByChannel =
                finalStatus.deliveries().stream()
                        .collect(java.util.stream.Collectors.toMap(DeliveryResponse::channel, DeliveryResponse::status));
        assertThat(statusByChannel).containsEntry("EMAIL", "DELIVERED").containsEntry("SMS", "FAILED");
    }

    @Test
    void getNotification_returns404_forUnknownId() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/notifications/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createNotification_returns400_whenUserIdMissing() {
        Map<String, Object> body = Map.of("type", "ORDER_SHIPPED", "payload", Map.of());

        ResponseEntity<String> response = restTemplate.postForEntity("/notifications", body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void preferences_areReadableAfterBeingSet() {
        String userId = newId("user");
        setPreference(userId, "ORDER_SHIPPED", Channel.EMAIL, Channel.PUSH);

        ResponseEntity<PreferenceResponse[]> response =
                restTemplate.getForEntity("/users/" + userId + "/preferences", PreferenceResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody()[0].channels()).containsExactlyInAnyOrder("EMAIL", "PUSH");
    }

    private NotificationCreatedResponse createNotification(String userId, String type, Map<String, String> payload) {
        Map<String, Object> body = Map.of("userId", userId, "type", type, "payload", payload);
        ResponseEntity<NotificationCreatedResponse> response =
                restTemplate.postForEntity("/notifications", body, NotificationCreatedResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody();
    }

    private void setPreference(String userId, String type, Channel... channels) {
        PreferenceRequest request = new PreferenceRequest(type, java.util.List.of(channels));
        ResponseEntity<PreferenceResponse> response =
                restTemplate.exchange(
                        "/users/" + userId + "/preferences",
                        org.springframework.http.HttpMethod.PUT,
                        new org.springframework.http.HttpEntity<>(request),
                        PreferenceResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private NotificationStatusResponse awaitTerminalStatus(String notificationId) {
        return await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(
                        () ->
                                restTemplate.getForEntity(
                                                "/notifications/" + notificationId, NotificationStatusResponse.class)
                                        .getBody(),
                        status -> !status.status().equals("IN_PROGRESS") && !status.status().equals("ACCEPTED"));
    }

    private String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
