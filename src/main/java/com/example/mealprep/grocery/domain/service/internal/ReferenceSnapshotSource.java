package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import com.example.mealprep.grocery.domain.entity.ReferencePriceRow;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Snapshot-backed {@link ReferencePriceSource} (01c) — reads {@code grocery_reference_prices}
 * (seeded from the bundled Open Prices starter set, rolled per-mapping-key by {@link
 * ReferenceProductMapper}) through the internal {@link PriceDataGateway} port. Package-private in
 * the {@code internal} pocket (the SPI is the only public surface) — mirrors discovery's
 * SPI-with-impl-in-the-pocket pattern.
 *
 * <p>Lookups normalise the key via {@link IngredientMappingKeys#normalise(String)} before querying,
 * so callers don't have to pre-normalise. {@code referencePrices} issues ONE {@code IN (...)} query
 * (the ≤5-SQL target for {@code getAggregatesByKeys}).
 */
@Component
class ReferenceSnapshotSource implements ReferencePriceSource {

  private final PriceDataGateway gateway;

  ReferenceSnapshotSource(PriceDataGateway gateway) {
    this.gateway = gateway;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ReferencePrice> referencePrice(String ingredientMappingKey) {
    String key = IngredientMappingKeys.normalise(ingredientMappingKey);
    if (key == null || key.isEmpty()) {
      return Optional.empty();
    }
    return gateway.findReferenceByKey(key).map(ReferenceSnapshotSource::toReferencePrice);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, ReferencePrice> referencePrices(Collection<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return Map.of();
    }
    List<String> normalised = new ArrayList<>(keys.size());
    for (String k : keys) {
      String n = IngredientMappingKeys.normalise(k);
      if (n != null && !n.isEmpty()) {
        normalised.add(n);
      }
    }
    if (normalised.isEmpty()) {
      return Map.of();
    }
    Map<String, ReferencePrice> out = new LinkedHashMap<>();
    for (ReferencePriceRow row : gateway.findReferencesByKeys(normalised)) {
      out.put(row.getIngredientMappingKey(), toReferencePrice(row));
    }
    return out;
  }

  private static ReferencePrice toReferencePrice(ReferencePriceRow row) {
    return new ReferencePrice(
        row.getIngredientMappingKey(),
        row.getReferenceUnitPence(),
        row.getUnit(),
        row.getReferenceConfidence(),
        row.getSourceAsOf().atStartOfDay(ZoneOffset.UTC).toInstant(),
        row.getAttribution());
  }
}
