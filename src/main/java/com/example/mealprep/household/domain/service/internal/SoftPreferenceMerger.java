package com.example.mealprep.household.domain.service.internal;

import com.example.mealprep.household.api.dto.LifestyleConfigDocument;
import com.example.mealprep.household.api.dto.MergeStrategy;
import com.example.mealprep.household.api.dto.MergedSoftPreferencesDto;
import com.example.mealprep.household.api.dto.SoftPreferenceBundleDto;
import com.example.mealprep.household.api.dto.TasteProfileDocument;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pure computation: takes per-user bundles + priorities, produces the merged document. Implements
 * the three merge rules from LLD line 441 — mean-weighted taste profile, set-union avoid list,
 * most-restrictive lifestyle — and degenerates to empty output cleanly when there are no bundles.
 */
@Component
public class SoftPreferenceMerger {

  /**
   * Warning sentinel pushed onto the merged {@code avoidList} when lifestyle windows can't
   * intersect.
   */
  static final String WINDOW_INTERSECTION_EMPTY = "WINDOW_INTERSECTION_EMPTY";

  /** Working precision for the weighted mean. 6 SF balances precision against output stability. */
  private static final MathContext MC = new MathContext(6, RoundingMode.HALF_UP);

  private final Clock clock;

  public SoftPreferenceMerger(Clock clock) {
    this.clock = clock;
  }

  public MergedSoftPreferencesDto merge(
      List<SoftPreferenceBundleDto> bundles,
      List<Integer> priorities,
      UUID householdId,
      List<UUID> contributingUserIds) {

    boolean allEmpty =
        bundles == null
            || bundles.isEmpty()
            || bundles.stream()
                .allMatch(b -> b.tasteProfile() == null && b.lifestyleConfig() == null);

    TasteProfileDocument mergedTaste =
        allEmpty ? emptyTasteProfile() : mergeTaste(bundles, priorities);
    LifestyleConfigDocument mergedLifestyle =
        allEmpty ? emptyLifestyleConfig() : mergeLifestyle(bundles, mergedTaste.avoidList());

    return new MergedSoftPreferencesDto(
        householdId,
        contributingUserIds,
        mergedTaste,
        mergedLifestyle,
        sortByPriorityDesc(contributingUserIds, priorities),
        MergeStrategy.MEAN_WEIGHTED_BY_PRIORITY,
        clock.instant());
  }

  // ---------------- taste profile ----------------

  private TasteProfileDocument mergeTaste(
      List<SoftPreferenceBundleDto> bundles, List<Integer> priorities) {
    Map<String, BigDecimal> ingredientLikes =
        weightedMean(bundles, priorities, tp -> tp == null ? null : tp.ingredientLikes());
    Map<String, BigDecimal> cuisineLikes =
        weightedMean(bundles, priorities, tp -> tp == null ? null : tp.cuisineLikes());

    TreeSet<String> avoidSet = new TreeSet<>();
    for (SoftPreferenceBundleDto b : bundles) {
      if (b == null || b.tasteProfile() == null || b.tasteProfile().avoidList() == null) {
        continue;
      }
      avoidSet.addAll(b.tasteProfile().avoidList());
    }
    return new TasteProfileDocument(ingredientLikes, cuisineLikes, new ArrayList<>(avoidSet));
  }

  private Map<String, BigDecimal> weightedMean(
      List<SoftPreferenceBundleDto> bundles,
      List<Integer> priorities,
      java.util.function.Function<TasteProfileDocument, Map<String, BigDecimal>> selector) {
    Map<String, BigDecimal> weightedSum = new LinkedHashMap<>();
    Map<String, BigDecimal> weightSum = new HashMap<>();
    for (int i = 0; i < bundles.size(); i++) {
      SoftPreferenceBundleDto b = bundles.get(i);
      if (b == null || b.tasteProfile() == null) {
        continue;
      }
      Map<String, BigDecimal> values = selector.apply(b.tasteProfile());
      if (values == null || values.isEmpty()) {
        continue;
      }
      BigDecimal priority = BigDecimal.valueOf(priorityAt(priorities, i));
      for (Map.Entry<String, BigDecimal> e : values.entrySet()) {
        if (e.getValue() == null) {
          continue;
        }
        weightedSum.merge(e.getKey(), e.getValue().multiply(priority, MC), (a, x) -> a.add(x, MC));
        weightSum.merge(e.getKey(), priority, (a, x) -> a.add(x, MC));
      }
    }
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> e : weightedSum.entrySet()) {
      BigDecimal denom = weightSum.get(e.getKey());
      if (denom == null || denom.signum() == 0) {
        continue;
      }
      out.put(e.getKey(), e.getValue().divide(denom, MC));
    }
    return out;
  }

  // ---------------- lifestyle ----------------

  private LifestyleConfigDocument mergeLifestyle(
      List<SoftPreferenceBundleDto> bundles, List<String> mergedAvoidList) {
    String latestStart = null;
    String earliestEnd = null;
    Integer minNovelty = null;
    boolean allBatch = true;
    boolean anyLifestyle = false;

    for (SoftPreferenceBundleDto b : bundles) {
      if (b == null || b.lifestyleConfig() == null) {
        continue;
      }
      anyLifestyle = true;
      LifestyleConfigDocument lc = b.lifestyleConfig();
      if (lc.mealTimingWindowStart() != null
          && (latestStart == null || lc.mealTimingWindowStart().compareTo(latestStart) > 0)) {
        latestStart = lc.mealTimingWindowStart();
      }
      if (lc.mealTimingWindowEnd() != null
          && (earliestEnd == null || lc.mealTimingWindowEnd().compareTo(earliestEnd) < 0)) {
        earliestEnd = lc.mealTimingWindowEnd();
      }
      if (lc.noveltyTolerancePercent() != null
          && (minNovelty == null || lc.noveltyTolerancePercent() < minNovelty)) {
        minNovelty = lc.noveltyTolerancePercent();
      }
      if (!lc.batchCookingPreferred()) {
        allBatch = false;
      }
    }
    if (!anyLifestyle) {
      return emptyLifestyleConfig();
    }
    if (latestStart != null
        && earliestEnd != null
        && latestStart.compareTo(earliestEnd) > 0
        && !mergedAvoidList.contains(WINDOW_INTERSECTION_EMPTY)) {
      mergedAvoidList.add(WINDOW_INTERSECTION_EMPTY);
    }
    return new LifestyleConfigDocument(latestStart, earliestEnd, minNovelty, allBatch);
  }

  // ---------------- helpers ----------------

  private static List<UUID> sortByPriorityDesc(List<UUID> userIds, List<Integer> priorities) {
    Integer[] idx = new Integer[userIds.size()];
    for (int i = 0; i < idx.length; i++) {
      idx[i] = i;
    }
    java.util.Arrays.sort(
        idx,
        (a, b) -> {
          int pa = priorityAt(priorities, a);
          int pb = priorityAt(priorities, b);
          if (pa != pb) {
            return Integer.compare(pb, pa); // DESC
          }
          return userIds.get(a).compareTo(userIds.get(b));
        });
    List<UUID> out = new ArrayList<>(userIds.size());
    for (Integer i : idx) {
      out.add(userIds.get(i));
    }
    return out;
  }

  private static int priorityAt(List<Integer> priorities, int i) {
    if (priorities == null || i >= priorities.size() || priorities.get(i) == null) {
      return 100;
    }
    return priorities.get(i);
  }

  static TasteProfileDocument emptyTasteProfile() {
    return new TasteProfileDocument(
        new LinkedHashMap<>(), new LinkedHashMap<>(), new ArrayList<>());
  }

  static LifestyleConfigDocument emptyLifestyleConfig() {
    return new LifestyleConfigDocument(null, null, null, false);
  }
}
