package com.example.mealprep.grocery.domain.service;

import com.example.mealprep.grocery.api.dto.PriceAggregateDto;
import com.example.mealprep.grocery.api.dto.PriceObservationDto;
import com.example.mealprep.grocery.api.dto.RecordManualPriceRequest;
import com.example.mealprep.grocery.api.dto.RefreshPricesRequest;
import com.example.mealprep.grocery.api.dto.RefreshPricesResultDto;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Tier 4 — price history. Public service contract (declarations only in 01a; implemented in
 * grocery-01c/01d/01g). Per lld/grocery.md lines 622-640. Aggregation reads return {@code
 * (estimate, confidence)} per the planner's contract — never raw observations.
 */
public interface PriceHistoryService {

  // Aggregation reads — used by the planner's cost sub-score and Tier 1's cost projection.
  Optional<PriceAggregateDto> getAggregate(
      UUID householdId, String ingredientMappingKey, String store);

  Map<String, PriceAggregateDto> getAggregatesByKeys(UUID householdId, Collection<String> keys);

  List<PriceAggregateDto> getCrossStoreAggregatesByKey(UUID householdId, String key);

  // Raw observation reads — audit / debug.
  Page<PriceObservationDto> getObservations(UUID userId, Pageable pageable);

  Page<PriceObservationDto> getObservationsByMappingKey(
      UUID householdId, String key, Pageable pageable);

  // Capture entry points.
  PriceObservationDto recordManualPrice(UUID userId, RecordManualPriceRequest request);

  RefreshPricesResultDto refreshOnDemand(UUID userId, RefreshPricesRequest request);

  // Scheduled background refresh — invoked by the @Scheduled job, not exposed via REST.
  void runScheduledBackgroundRefresh(UUID userId);
}
