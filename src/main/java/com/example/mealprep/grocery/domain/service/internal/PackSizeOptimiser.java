package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.domain.entity.PackSizeHeuristic;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tier-1 pack-size chooser (grocery-01b). Given a remaining demand and the candidate {@link
 * PackSizeHeuristic} packs (caller pre-sorts so key-match packs precede category-match packs and
 * the list is rank-ascending = smallest first), picks the smallest combination of a SINGLE pack
 * size that meets the demand. Per lld/grocery.md line 864 + test fixtures line 1015.
 *
 * <p>Strategy:
 *
 * <ul>
 *   <li><b>Non-perishable</b> — greedy largest-down: pick the largest single pack that still
 *       minimises total purchased weight while meeting demand (prefer fewest packs, then least
 *       over-buy). 750 g flour over {500 g, 1 kg} → 1×1 kg (one pack beats 2×500 g); 1.5 kg over
 *       {500 g, 1 kg, 1.5 kg} → 1×1.5 kg (one pack beats 3×500 g / 1×1 kg+1×500 g).
 *   <li><b>Perishable</b> — smallest-up: prefer the smallest pack that meets demand to avoid waste;
 *       only step up to a larger single pack when the smallest cannot (count-wise) be combined to
 *       reach demand without excess.
 * </ul>
 *
 * <p>Package-private internal plumbing — invoked only by {@link ShoppingListCalculator}.
 */
@Component
class PackSizeOptimiser {

  /** The chosen pack: a size (g/ml) OR a count, the unit, and how many of that pack to buy. */
  record PackChoice(Integer packSizeG, Integer packCount, String packUnit, int packsToBuy) {

    static PackChoice none() {
      return new PackChoice(null, null, null, 0);
    }

    boolean isEmpty() {
      return packsToBuy == 0;
    }
  }

  /**
   * Smallest single-pack combination that meets {@code remaining}. {@code packs} is pre-sorted by
   * the caller (key-match before category-match, rank ascending). {@code perishable} flips the
   * greedy direction. Returns {@link PackChoice#none()} when no pack is available.
   */
  PackChoice choose(IngredientDemand remaining, List<PackSizeHeuristic> packs, boolean perishable) {
    if (packs == null || packs.isEmpty() || remaining == null) {
      return PackChoice.none();
    }
    double needed = remaining.quantity().doubleValue();
    if (needed <= 0) {
      return PackChoice.none();
    }

    PackChoice best = null;
    for (PackSizeHeuristic pack : packs) {
      double unitMagnitude = magnitudeOf(pack);
      if (unitMagnitude <= 0) {
        continue;
      }
      int count = (int) Math.ceil(needed / unitMagnitude);
      if (count < 1) {
        count = 1;
      }
      PackChoice candidate =
          new PackChoice(pack.getPackSizeG(), pack.getPackCount(), pack.getPackUnit(), count);
      best = better(best, candidate, unitMagnitude, perishable);
    }
    return best == null ? PackChoice.none() : best;
  }

  /**
   * Prefer the candidate that buys the least total magnitude (least waste). On an equal-total tie
   * the perishability preference decides: NON-perishable prefers the LARGER single pack (fewest
   * packs — greedy largest-down); perishable prefers the SMALLER single pack (smallest-up, so a
   * smaller unit spoils less and stays fresher), accepting more packs for the same total.
   */
  private PackChoice better(
      PackChoice current, PackChoice candidate, double candidateUnitMagnitude, boolean perishable) {
    if (current == null) {
      return candidate;
    }
    double currentTotal = totalMagnitude(current);
    double candidateTotal = candidateUnitMagnitude * candidate.packsToBuy();

    if (candidateTotal < currentTotal - 1e-9) {
      return candidate;
    }
    if (candidateTotal > currentTotal + 1e-9) {
      return current;
    }
    // Equal total purchased magnitude: the perishability preference decides on unit size.
    double currentUnit = currentTotal / Math.max(1, current.packsToBuy());
    if (Math.abs(candidateUnitMagnitude - currentUnit) < 1e-9) {
      // Identical unit size too → fewest packs (covers the key-match-first pre-sorted tie).
      return candidate.packsToBuy() < current.packsToBuy() ? candidate : current;
    }
    if (perishable) {
      return candidateUnitMagnitude < currentUnit ? candidate : current; // smaller-up
    }
    return candidateUnitMagnitude > currentUnit ? candidate : current; // largest-down
  }

  private static double totalMagnitude(PackChoice choice) {
    double unit = choice.packSizeG() != null ? choice.packSizeG() : choice.packCount();
    return unit * choice.packsToBuy();
  }

  /** Magnitude per pack: the weight/volume in grams/ml if present, otherwise the count. */
  private static double magnitudeOf(PackSizeHeuristic pack) {
    if (pack.getPackSizeG() != null) {
      return pack.getPackSizeG();
    }
    if (pack.getPackCount() != null) {
      return pack.getPackCount();
    }
    return 0;
  }

  /** Helper for callers that hold a {@link BigDecimal} demand. */
  static IngredientDemand demand(String key, String displayName, BigDecimal qty, String unit) {
    return new IngredientDemand(key, displayName, qty, unit, null, null);
  }
}
