package com.notifications.service;

import com.notifications.dto.PreferenceRequest;
import com.notifications.entity.UserPreference;
import com.notifications.repository.UserPreferenceRepository;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Preferences are owned separately from delivery because routing (deciding where a notification
 * should go) is a distinct responsibility from actually sending it.
 */
@Service
public class PreferenceService {

    private final UserPreferenceRepository preferenceRepository;

    public PreferenceService(UserPreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    @Transactional
    public UserPreference setPreference(String userId, PreferenceRequest request) {
        UserPreference preference =
                preferenceRepository
                        .findByUserIdAndNotificationType(userId, request.notificationType())
                        .orElseGet(
                                () ->
                                        UserPreference.builder()
                                                .userId(userId)
                                                .notificationType(request.notificationType())
                                                .build());

        preference.setChannels(new HashSet<>(request.channels()));
        return preferenceRepository.save(preference);
    }

    @Transactional(readOnly = true)
    public List<UserPreference> getPreferences(String userId) {
        return preferenceRepository.findByUserId(userId);
    }
}
