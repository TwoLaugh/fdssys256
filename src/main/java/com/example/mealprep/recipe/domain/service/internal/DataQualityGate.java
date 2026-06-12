package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.domain.entity.DataQuality;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Ranks data-quality tiers and expands an ordinal <b>floor</b> into the set of acceptable tiers
 * (lld/recipe.md §Data-quality gates). The pinned ordering is:
 *
 * <pre>USER_VERIFIED &gt; IMPORTED ≈ AI_GENERATED &gt; WEB_DISCOVERED</pre>
 *
 * <p>{@code IMPORTED} and {@code AI_GENERATED} are <b>tied</b>: a floor at either admits both (plus
 * everything above), so {@code minDataQuality=IMPORTED} and {@code minDataQuality=AI_GENERATED}
 * yield the same set — the only tier either excludes is {@code WEB_DISCOVERED}. A {@code
 * minDataQuality} of {@code null} or {@code WEB_DISCOVERED} admits every tier.
 */
public final class DataQualityGate {

  /** Rank per tier; higher is more trusted. The IMPORTED ≈ AI_GENERATED tie shares rank 1. */
  private static int rank(DataQuality quality) {
    return switch (quality) {
      case USER_VERIFIED -> 2;
      case IMPORTED, AI_GENERATED -> 1;
      case WEB_DISCOVERED -> 0;
    };
  }

  private DataQualityGate() {}

  /**
   * The tiers at or above {@code floor} per the pinned ordering. {@code null} floor → all tiers.
   * Never empty (the floor itself is always included).
   */
  public static List<DataQuality> atOrAbove(DataQuality floor) {
    if (floor == null) {
      return List.copyOf(EnumSet.allOf(DataQuality.class));
    }
    int floorRank = rank(floor);
    Set<DataQuality> accepted = EnumSet.noneOf(DataQuality.class);
    for (DataQuality candidate : DataQuality.values()) {
      if (rank(candidate) >= floorRank) {
        accepted.add(candidate);
      }
    }
    return List.copyOf(accepted);
  }
}
