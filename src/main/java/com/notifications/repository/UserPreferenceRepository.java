package com.notifications.repository;

import com.notifications.entity.UserPreference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    Optional<UserPreference> findByUserIdAndNotificationType(String userId, String notificationType);

    List<UserPreference> findByUserId(String userId);
}
