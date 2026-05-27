package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import com.example.mealprep.grocery.domain.entity.PackSizeHeuristic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Tier-1 pack-size heuristic resolver (grocery-01b). Looks up candidate packs by NORMALISED
 * ingredient mapping key first, then falls back to the ingredient category — per lld/grocery.md
 * step 3 ("mapping key, then category fallback"). Returns the packs rank-ascending (smallest first)
 * so {@link PackSizeOptimiser} can reason about them directly; key-match packs are returned ahead
 * of category packs because a key match wins over a category match.
 *
 * <p>Reads go through {@link ShoppingListDataGateway}; {@link #preload(Iterable)} loads all
 * key-matched packs for a set of keys in advance to keep the calculator within the ≤5-SQL budget.
 * Package-private internal plumbing.
 */
@Component
class PackSizeHeuristicLookup {

  private final ShoppingListDataGateway gateway;

  PackSizeHeuristicLookup(ShoppingListDataGateway gateway) {
    this.gateway = gateway;
  }

  /**
   * Candidate packs for {@code mappingKey} (and optional {@code category} fallback),
   * rank-ascending, key-match packs first. Empty when neither the key nor the category has any
   * seeded pack.
   */
  List<PackSizeHeuristic> resolve(String mappingKey, String category) {
    List<PackSizeHeuristic> out = new ArrayList<>();
    String key = IngredientMappingKeys.normalise(mappingKey);
    if (key != null && !key.isEmpty()) {
      out.addAll(gateway.findPacksByKey(key));
    }
    if (out.isEmpty() && category != null && !category.isBlank()) {
      out.addAll(gateway.findPacksByCategory(category));
    }
    return out;
  }

  /**
   * Batch-resolve the key-matched packs for every key in one cache so the calculator does not issue
   * one query per line. Category fallbacks (rare; only staples carry a category) are resolved
   * lazily via {@link #resolve}. Returns a key → rank-ascending-packs map (only keys with at least
   * one pack are present).
   */
  Map<String, List<PackSizeHeuristic>> preload(Iterable<String> normalisedKeys) {
    Map<String, List<PackSizeHeuristic>> byKey = new LinkedHashMap<>();
    for (String key : normalisedKeys) {
      if (key == null || key.isEmpty() || byKey.containsKey(key)) {
        continue;
      }
      List<PackSizeHeuristic> packs = gateway.findPacksByKey(key);
      if (!packs.isEmpty()) {
        byKey.put(key, packs);
      }
    }
    return byKey;
  }
}
