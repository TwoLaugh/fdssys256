package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.domain.entity.PriceObservation;
import com.example.mealprep.grocery.domain.entity.ReferencePriceRow;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Internal data-access seam for the Tier-4 price capability (01c). Exists because the grocery
 * repositories are package-private in {@code domain.repository} (enforced by {@code
 * GroceryBoundaryTest.reposArePackagePrivate}) and therefore not visible to {@code
 * domain.service.internal}. The package-private {@code PriceDataGatewayImpl} (co-located WITH the
 * repositories in {@code domain.repository}) implements this port and holds the repositories; the
 * Tier-4 services depend only on this public port — the repositories never leak past the package
 * boundary. Mirrors the Spring-Modulith "package-private repository behind an internal port"
 * pattern.
 */
public interface PriceDataGateway {

  // ---- price-observation reads (consumed by PriceAggregator / GroceryServiceImpl) ----

  List<PriceObservation> findRecentByKey(UUID householdId, String key, Instant since);

  List<PriceObservation> findRecentByKeyAcrossStores(UUID householdId, String key, Instant since);

  List<PriceObservation> findRecentByKeys(UUID householdId, Collection<String> keys, Instant since);

  Page<PriceObservation> findObservationsByUser(UUID userId, Pageable pageable);

  Page<PriceObservation> findObservationsByHouseholdKey(
      UUID householdId, String key, Pageable pageable);

  // ---- price-observation write (append-only) ----

  PriceObservation save(PriceObservation observation);

  // ---- reference-price reads (consumed by ReferenceSnapshotSource) ----

  Optional<ReferencePriceRow> findReferenceByKey(String ingredientMappingKey);

  List<ReferencePriceRow> findReferencesByKeys(Collection<String> keys);
}
