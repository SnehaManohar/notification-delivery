package com.notifications.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.notifications.entity.UserPreference;
import com.notifications.model.Channel;
import com.notifications.repository.UserPreferenceRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreferenceBasedNotificationRouterTest {

    @Mock private UserPreferenceRepository preferenceRepository;

    @Test
    void returnsConfiguredChannels_whenPreferenceExists() {
        UserPreference preference =
                UserPreference.builder()
                        .userId("u1")
                        .notificationType("ORDER_SHIPPED")
                        .channels(Set.of(Channel.EMAIL, Channel.SMS))
                        .build();
        when(preferenceRepository.findByUserIdAndNotificationType("u1", "ORDER_SHIPPED"))
                .thenReturn(Optional.of(preference));

        PreferenceBasedNotificationRouter router = new PreferenceBasedNotificationRouter(preferenceRepository);

        assertThat(router.getRoutes("u1", "ORDER_SHIPPED")).containsExactlyInAnyOrder(Channel.EMAIL, Channel.SMS);
    }

    @Test
    void returnsDefaultChannel_whenNoPreferenceStored() {
        when(preferenceRepository.findByUserIdAndNotificationType("u1", "SECURITY_ALERT"))
                .thenReturn(Optional.empty());

        PreferenceBasedNotificationRouter router = new PreferenceBasedNotificationRouter(preferenceRepository);

        assertThat(router.getRoutes("u1", "SECURITY_ALERT"))
                .isEqualTo(PreferenceBasedNotificationRouter.DEFAULT_CHANNELS);
    }

    @Test
    void returnsDefaultChannel_whenPreferenceHasNoChannelsSelected() {
        UserPreference preference =
                UserPreference.builder().userId("u1").notificationType("MARKETING").channels(Set.of()).build();
        when(preferenceRepository.findByUserIdAndNotificationType("u1", "MARKETING"))
                .thenReturn(Optional.of(preference));

        PreferenceBasedNotificationRouter router = new PreferenceBasedNotificationRouter(preferenceRepository);

        assertThat(router.getRoutes("u1", "MARKETING")).isEqualTo(PreferenceBasedNotificationRouter.DEFAULT_CHANNELS);
    }
}
