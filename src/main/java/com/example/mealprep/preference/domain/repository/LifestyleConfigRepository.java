package com.example.mealprep.preference.domain.repository;

import com.example.mealprep.preference.domain.entity.LifestyleConfig;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link LifestyleConfig}. Cross-module callers go through {@code
 * LifestyleConfigQueryService} / {@code LifestyleConfigUpdateService} rather than reaching into
 * this repo directly.
 *
 * <p>{@link #findByLastReviewPromptAtBeforeOrLastReviewPromptAtIsNull(Instant)} is provisioned for
 * the deferred behavioural-drift scanner (C-B-046) — kept here so 01d doesn't reshape the
 * repository when the scanner ticket lands.
 */
public interface LifestyleConfigRepository extends JpaRepository<LifestyleConfig, UUID> {

  Optional<LifestyleConfig> findByUserId(UUID userId);

  List<LifestyleConfig> findByUserIdIn(Collection<UUID> userIds);

  /** Used by the deferred behavioural-drift scanner (C-B-046). */
  List<LifestyleConfig> findByLastReviewPromptAtBeforeOrLastReviewPromptAtIsNull(Instant threshold);
}
