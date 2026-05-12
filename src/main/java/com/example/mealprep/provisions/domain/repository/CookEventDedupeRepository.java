package com.example.mealprep.provisions.domain.repository;

import com.example.mealprep.provisions.domain.entity.ProvisionCookEventDedupe;
import com.example.mealprep.provisions.domain.entity.ProvisionCookEventDedupe.CookEventDedupeId;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link ProvisionCookEventDedupe}. Gating writes for cook-event idempotency; daily
 * sweep query for retention. Cross-module callers go through {@code ProvisionUpdateService}; this
 * repo is internal per {@code ProvisionsBoundaryTest}.
 */
public interface CookEventDedupeRepository
    extends JpaRepository<ProvisionCookEventDedupe, CookEventDedupeId> {

  /** Idempotency check — true when the {@code (mealSlotId, dedupeKey)} row already exists. */
  boolean existsByIdMealSlotIdAndIdDedupeKey(UUID mealSlotId, String dedupeKey);

  /**
   * Deletes rows whose {@code created_at} precedes the supplied cutoff. Returns the affected row
   * count for logging.
   */
  @Modifying
  @Query("delete from ProvisionCookEventDedupe d where d.createdAt < :cutoff")
  int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
