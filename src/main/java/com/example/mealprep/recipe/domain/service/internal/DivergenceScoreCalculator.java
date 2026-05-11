package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Provisional fingerprint-jaccard-distance divergence calculator. LLD §lld/recipe.md line 838
 * defers the canonical formula to the pipeline LLD; this component is the single point of
 * replacement when that lands.
 *
 * <p>Mean of 6 component distances: 4 Jaccard distances on the list fields + 2 scalar mismatches.
 * Result rounded HALF_UP to 3 decimal places so it fits the {@code numeric(4,3)} column.
 */
@Component
public class DivergenceScoreCalculator {

  /**
   * Compute the 0..1 divergence between {@code parent} and {@code child}. Both null → 0; one null →
   * 1.
   */
  public BigDecimal compute(CharacterFingerprintDto parent, CharacterFingerprintDto child) {
    if (parent == null && child == null) {
      return BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
    }
    if (parent == null || child == null) {
      return BigDecimal.ONE.setScale(3, RoundingMode.HALF_UP);
    }
    double sum = 0.0;
    sum += jaccardDistance(parent.definingIngredients(), child.definingIngredients());
    sum += jaccardDistance(parent.definingTechniques(), child.definingTechniques());
    sum += jaccardDistance(parent.textureEssentials(), child.textureEssentials());
    sum += jaccardDistance(parent.flavourAnchors(), child.flavourAnchors());
    sum += parent.complexityTier() == child.complexityTier() ? 0.0 : 1.0;
    sum += Objects.equals(parent.cuisineAnchor(), child.cuisineAnchor()) ? 0.0 : 1.0;
    return BigDecimal.valueOf(sum / 6.0).setScale(3, RoundingMode.HALF_UP);
  }

  private static double jaccardDistance(List<String> a, List<String> b) {
    Set<String> aSet = a == null ? Set.of() : new HashSet<>(a);
    Set<String> bSet = b == null ? Set.of() : new HashSet<>(b);
    if (aSet.isEmpty() && bSet.isEmpty()) {
      return 0.0;
    }
    Set<String> union = new HashSet<>(aSet);
    union.addAll(bSet);
    Set<String> intersection = new HashSet<>(aSet);
    intersection.retainAll(bSet);
    if (union.isEmpty()) {
      return 0.0;
    }
    return 1.0 - ((double) intersection.size() / union.size());
  }
}
