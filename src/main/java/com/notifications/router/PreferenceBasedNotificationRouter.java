package com.notifications.router;

import com.notifications.entity.UserPreference;
import com.notifications.model.Channel;
import com.notifications.repository.UserPreferenceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves routes purely from the user's stored preference for the given notification type.
 * If the user has never set a preference for this type, we fall back to a single default
 * channel (EMAIL) rather than silently sending nothing.
 */
@Component
public class PreferenceBasedNotificationRouter implements NotificationRouter {

    static final List<Channel> DEFAULT_CHANNELS = List.of(Channel.EMAIL);

    private final UserPreferenceRepository preferenceRepository;

    public PreferenceBasedNotificationRouter(UserPreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    @Override
    public List<Channel> getRoutes(String userId, String notificationType) {
        Optional<UserPreference> preference =
                preferenceRepository.findByUserIdAndNotificationType(userId, notificationType);

        if (preference.isEmpty() || preference.get().getChannels().isEmpty()) {
            return DEFAULT_CHANNELS;
        }

        return new ArrayList<>(preference.get().getChannels());
    }
}
