package com.example.mealprep.notification.domain.repository;

import com.example.mealprep.notification.domain.entity.NotificationPreference;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link NotificationPreference}. Module-private by package convention; isolation is
 * enforced by {@code NotificationBoundaryTest}.
 */
public interface NotificationPreferenceRepository
    extends JpaRepository<NotificationPreference, UUID> {

  Optional<NotificationPreference> findByUserId(UUID userId);
}
