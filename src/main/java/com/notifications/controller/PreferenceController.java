package com.notifications.controller;

import com.notifications.dto.PreferenceRequest;
import com.notifications.dto.PreferenceResponse;
import com.notifications.entity.UserPreference;
import com.notifications.service.PreferenceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/{userId}/preferences")
public class PreferenceController {

    private final PreferenceService preferenceService;

    public PreferenceController(PreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @PutMapping
    public PreferenceResponse put(@PathVariable String userId, @Valid @RequestBody PreferenceRequest request) {
        UserPreference saved = preferenceService.setPreference(userId, request);
        return toResponse(saved);
    }

    @GetMapping
    public List<PreferenceResponse> get(@PathVariable String userId) {
        return preferenceService.getPreferences(userId).stream().map(this::toResponse).toList();
    }

    private PreferenceResponse toResponse(UserPreference preference) {
        return new PreferenceResponse(
                preference.getUserId(),
                preference.getNotificationType(),
                preference.getChannels().stream().map(Enum::name).toList());
    }
}
