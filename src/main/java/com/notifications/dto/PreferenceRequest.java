package com.notifications.dto;

import com.notifications.model.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record PreferenceRequest(
        @NotBlank String notificationType, @NotEmpty List<Channel> channels) {}
