package com.notifications.dto;

import java.util.List;

public record PreferenceResponse(String userId, String notificationType, List<String> channels) {}
