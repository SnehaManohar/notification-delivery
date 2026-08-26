package com.notifications.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record NotificationRequest(
        @NotBlank String userId, @NotBlank String type, Map<String, String> payload) {}
