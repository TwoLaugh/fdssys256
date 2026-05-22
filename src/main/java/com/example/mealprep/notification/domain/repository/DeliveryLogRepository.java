package com.example.mealprep.notification.domain.repository;

import com.example.mealprep.notification.domain.entity.DeliveryLog;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the append-only {@link DeliveryLog}. Module-private by package convention;
 * isolation is enforced by {@code NotificationBoundaryTest}.
 */
public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, UUID> {

  Page<DeliveryLog> findByNotificationIdOrderByAttemptedAtDesc(UUID notificationId, Pageable p);
}
