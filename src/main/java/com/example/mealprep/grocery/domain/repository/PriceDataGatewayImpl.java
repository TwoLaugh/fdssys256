package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.PriceObservation;
import com.example.mealprep.grocery.domain.entity.ReferencePriceRow;
import com.example.mealprep.grocery.domain.service.internal.PriceDataGateway;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Package-private adapter implementing the public {@link PriceDataGateway} port. Co-located with
 * the package-private grocery repositories so it can hold them (they are not visible outside this
 * package — {@code GroceryBoundaryTest.reposArePackagePrivate}). The Tier-4 services in {@code
 * domain.service.internal} inject the port, never the repositories, so the repositories never
 * escape the package boundary. Class is package-private (satisfies the "no public class in
 * domain.repository" rule); it is NOT a {@code *ServiceImpl} so the impls-in-internal rule does not
 * apply.
 */
@Component
class PriceDataGatewayImpl implements PriceDataGateway {

  private final PriceObservationRepository observationRepository;
  private final ReferencePriceRowRepository referenceRepository;

  PriceDataGatewayImpl(
      PriceObservationRepository observationRepository,
      ReferencePriceRowRepository referenceRepository) {
    this.observationRepository = observationRepository;
    this.referenceRepository = referenceRepository;
  }

  @Override
  public List<PriceObservation> findRecentByKey(UUID householdId, String key, Instant since) {
    return observationRepository.findRecentByKey(householdId, key, since);
  }

  @Override
  public List<PriceObservation> findRecentByKeyAcrossStores(
      UUID householdId, String key, Instant since) {
    return observationRepository.findRecentByKeyAcrossStores(householdId, key, since);
  }

  @Override
  public List<PriceObservation> findRecentByKeys(
      UUID householdId, Collection<String> keys, Instant since) {
    return observationRepository.findRecentByKeys(householdId, keys, since);
  }

  @Override
  public Page<PriceObservation> findObservationsByUser(UUID userId, Pageable pageable) {
    return observationRepository.findAllByUserIdOrderByObservedAtDesc(userId, pageable);
  }

  @Override
  public Page<PriceObservation> findObservationsByHouseholdKey(
      UUID householdId, String key, Pageable pageable) {
    return observationRepository.findAllByHouseholdIdAndIngredientMappingKeyOrderByObservedAtDesc(
        householdId, key, pageable);
  }

  @Override
  public PriceObservation save(PriceObservation observation) {
    return observationRepository.save(observation);
  }

  @Override
  public Optional<ReferencePriceRow> findReferenceByKey(String ingredientMappingKey) {
    return referenceRepository.findByIngredientMappingKey(ingredientMappingKey);
  }

  @Override
  public List<ReferencePriceRow> findReferencesByKeys(Collection<String> keys) {
    return referenceRepository.findByIngredientMappingKeyIn(keys);
  }
}
