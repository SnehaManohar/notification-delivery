package com.notifications.repository;

import com.notifications.entity.DeadLetterEntry;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterEntryRepository extends JpaRepository<DeadLetterEntry, Long> {

    Optional<DeadLetterEntry> findByDeliveryId(String deliveryId);
}
