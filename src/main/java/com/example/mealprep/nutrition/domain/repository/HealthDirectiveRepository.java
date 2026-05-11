package com.example.mealprep.nutrition.domain.repository;

import com.example.mealprep.nutrition.api.dto.DirectiveStatus;
import com.example.mealprep.nutrition.domain.entity.HealthDirective;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link HealthDirective}. {@code public} for the same reason as the
 * other nutrition repos — cross-module reach-through is fenced by {@code NutritionBoundaryTest},
 * not Java visibility.
 *
 * <p>{@link #findByStatusAndAutoExpiresAtBefore} ships for the deferred auto-expiry sweep (LLD line
 * 1022) — no caller in 01e.
 */
public interface HealthDirectiveRepository extends JpaRepository<HealthDirective, UUID> {

  Optional<HealthDirective> findBySourcePlatformAndExternalDirectiveId(
      String sourcePlatform, String externalDirectiveId);

  Page<HealthDirective> findByUserIdAndStatusOrderByReceivedAtDesc(
      UUID userId, DirectiveStatus status, Pageable pageable);

  Page<HealthDirective> findByUserIdOrderByReceivedAtDesc(UUID userId, Pageable pageable);

  List<HealthDirective> findByStatusAndAutoExpiresAtBefore(DirectiveStatus status, Instant cutoff);
}
