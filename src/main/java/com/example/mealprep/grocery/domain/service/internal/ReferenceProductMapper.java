package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Tier 4 (01c) — rolls per-barcoded-product Open Prices rows up to ONE per-mapping-key reference
 * estimate. Open Prices rows are {@code (barcode/product, price, ...)} — NOT keyed by our {@code
 * ingredient_mapping_key} — so this mapper is the load-bearing product→category aggregation layer
 * (flagged "Worth user review" in the ticket: the mapping table is curated reference data; v1 ships
 * the e2e-fixture starter set).
 *
 * <p>Pure + deterministic: groups products by their (normalised) target mapping key, then collapses
 * each group to the integer-rounded MEAN normalised unit price across the group's products. The
 * grocery seed migration ships rows already rolled up to this shape; this mapper is the same rollup
 * expressed as code so the logic is unit-tested ({@code ReferenceProductMapperTest}) and so a
 * future full Open Prices import can roll a raw product dump through the identical path.
 *
 * <p>Package-private — internal plumbing behind {@code ReferenceSnapshotSource}.
 */
@Component
class ReferenceProductMapper {

  /**
   * A single per-product Open Prices row: which mapping key it rolls up to, its normalised unit
   * price (pence) and the unit that price is expressed in.
   */
  record ReferenceProduct(String ingredientMappingKey, int unitPence, String unit) {}

  /** A rolled-up per-mapping-key estimate. */
  record RolledReference(
      String ingredientMappingKey, int unitPence, String unit, int sampleProducts) {}

  /**
   * Roll a flat list of per-product rows up to one estimate per (normalised) mapping key. Products
   * with a blank key are dropped. The estimate's {@code unit} is the most-common unit in the group
   * (ties broken by first-seen); only products matching that unit contribute to the mean so the
   * pence figure stays unit-consistent. Insertion order of first appearance is preserved.
   */
  Map<String, RolledReference> roll(List<ReferenceProduct> products) {
    Map<String, List<ReferenceProduct>> byKey = new LinkedHashMap<>();
    for (ReferenceProduct p : products) {
      String key = IngredientMappingKeys.normalise(p.ingredientMappingKey());
      if (key == null || key.isEmpty()) {
        continue;
      }
      byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
    }
    Map<String, RolledReference> out = new LinkedHashMap<>();
    for (Map.Entry<String, List<ReferenceProduct>> e : byKey.entrySet()) {
      String unit = dominantUnit(e.getValue());
      long sum = 0;
      int n = 0;
      for (ReferenceProduct p : e.getValue()) {
        if (unit.equals(p.unit())) {
          sum += p.unitPence();
          n++;
        }
      }
      int mean = (int) Math.round((double) sum / n);
      out.put(e.getKey(), new RolledReference(e.getKey(), mean, unit, n));
    }
    return out;
  }

  /** Most-frequent unit in the group; ties resolved by first appearance (deterministic). */
  private static String dominantUnit(List<ReferenceProduct> products) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (ReferenceProduct p : products) {
      counts.merge(p.unit(), 1, Integer::sum);
    }
    String best = null;
    int bestCount = -1;
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      if (e.getValue() > bestCount) {
        best = e.getKey();
        bestCount = e.getValue();
      }
    }
    return best;
  }
}
