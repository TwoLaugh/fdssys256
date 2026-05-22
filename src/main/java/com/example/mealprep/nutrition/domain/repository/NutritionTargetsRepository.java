package com.example.mealprep.nutrition.domain.repository;

import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link NutritionTargets}. Cross-module callers go through {@code
 * NutritionQueryService} / {@code NutritionUpdateService} — enforced by {@code
 * NutritionBoundaryTest} (ArchUnit), the architectural boundary mechanism.
 *
 * <p>The interface is {@code public} only because the in-module {@code domain.service.internal}
 * package needs to inject it; package-private would prevent any reference from another package,
 * including same-module ones. The boundary test, not Java visibility, fences cross-module
 * reach-through.
 *
 * <p>Notably no multi-attribute {@code @EntityGraph} — the aggregate has three list children
 * ({@code perMealDistribution}, {@code microTargets}, {@code activityAdjustments}) and Hibernate
 * throws {@code MultipleBagFetchException} when more than one is fetched eagerly. The service
 * touches each collection inside {@code @Transactional(readOnly = true)} to force lazy load (4
 * SELECTs total — one root + three list bags; the {@code EatingWindow} {@code @OneToOne} joins on
 * the root SELECT).
 */
public interface NutritionTargetsRepository extends JpaRepository<NutritionTargets, UUID> {

  Optional<NutritionTargets> findByUserId(UUID userId);

  /**
   * Distinct {@code user_id}s with a targets row. Drives the notification/01b {@code
   * NutritionAlertScanner}'s per-user sweep.
   */
  @org.springframework.data.jpa.repository.Query("SELECT DISTINCT t.userId FROM NutritionTargets t")
  java.util.List<UUID> findDistinctUserIds();
}
