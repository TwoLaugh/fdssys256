package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.PriceObservation;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link PriceObservation} (append-only Tier-4 price history).
 * Package-private. Verbatim from lld/grocery.md lines 513-535 — the four aggregation reads consumed
 * by {@code PriceAggregator}, plus the {@code findRecentByKeys} batch sibling per the style guide.
 */
interface PriceObservationRepository extends JpaRepository<PriceObservation, UUID> {

  @Query(
      """
      select p from PriceObservation p
      where p.householdId = :householdId and p.ingredientMappingKey = :key
        and p.observedAt >= :since
      order by p.observedAt desc""")
  List<PriceObservation> findRecentByKey(
      @Param("householdId") UUID householdId,
      @Param("key") String key,
      @Param("since") Instant since);

  @Query(
      """
      select p from PriceObservation p
      where p.householdId = :householdId and p.ingredientMappingKey in :keys
        and p.observedAt >= :since""")
  List<PriceObservation> findRecentByKeys(
      @Param("householdId") UUID householdId,
      @Param("keys") Collection<String> keys,
      @Param("since") Instant since);

  @Query(
      """
      select max(p.observedAt) from PriceObservation p
      where p.householdId = :householdId and p.ingredientMappingKey = :key""")
  Optional<Instant> findLatestObservedAt(
      @Param("householdId") UUID householdId, @Param("key") String key);

  Page<PriceObservation> findAllByUserIdOrderByObservedAtDesc(UUID userId, Pageable p);

  Page<PriceObservation> findAllByHouseholdIdAndIngredientMappingKeyOrderByObservedAtDesc(
      UUID householdId, String ingredientMappingKey, Pageable p);

  @Query(
      """
      select p from PriceObservation p
      where p.householdId = :householdId and p.ingredientMappingKey = :key
        and p.observedAt >= :since""")
  List<PriceObservation> findRecentByKeyAcrossStores(
      @Param("householdId") UUID householdId,
      @Param("key") String key,
      @Param("since") Instant since);
}
