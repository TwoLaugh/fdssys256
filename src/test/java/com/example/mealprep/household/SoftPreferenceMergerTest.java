package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.household.api.dto.LifestyleConfigDocument;
import com.example.mealprep.household.api.dto.MergeStrategy;
import com.example.mealprep.household.api.dto.MergedSoftPreferencesDto;
import com.example.mealprep.household.api.dto.SoftPreferenceBundleDto;
import com.example.mealprep.household.api.dto.TasteProfileDocument;
import com.example.mealprep.household.domain.service.internal.SoftPreferenceMerger;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SoftPreferenceMerger} (LLD §SoftPreferenceMergerTest line 469): equal- and
 * differing-priority weighted mean, avoid-list union, single-user degenerate, most-restrictive
 * lifestyle, empty-intersection window warning, all-empty branch.
 */
class SoftPreferenceMergerTest {

  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-08T10:00:00Z"), ZoneOffset.UTC);
  private final SoftPreferenceMerger merger = new SoftPreferenceMerger(fixedClock);

  @Test
  void merge_emptyBundles_returnsEmptyDocsAndStrategy() {
    UUID hh = UUID.randomUUID();
    UUID u1 = UUID.randomUUID();
    MergedSoftPreferencesDto out = merger.merge(List.of(), List.of(100), hh, List.of(u1));

    assertThat(out.householdId()).isEqualTo(hh);
    assertThat(out.contributingUserIds()).containsExactly(u1);
    assertThat(out.mergedTasteProfile().ingredientLikes()).isEmpty();
    assertThat(out.mergedTasteProfile().cuisineLikes()).isEmpty();
    assertThat(out.mergedTasteProfile().avoidList()).isEmpty();
    assertThat(out.mergedLifestyleConfig().mealTimingWindowStart()).isNull();
    assertThat(out.mergedLifestyleConfig().mealTimingWindowEnd()).isNull();
    assertThat(out.mergedLifestyleConfig().noveltyTolerancePercent()).isNull();
    assertThat(out.mergedLifestyleConfig().batchCookingPreferred()).isFalse();
    assertThat(out.strategy()).isEqualTo(MergeStrategy.MEAN_WEIGHTED_BY_PRIORITY);
    assertThat(out.mergedAt()).isEqualTo(Instant.parse("2026-05-08T10:00:00Z"));
  }

  @Test
  void merge_allNullFieldsBundles_returnsEmptyDocs() {
    UUID hh = UUID.randomUUID();
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 = new SoftPreferenceBundleDto(u1, null, null);
    SoftPreferenceBundleDto b2 = new SoftPreferenceBundleDto(u2, null, null);

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 200), hh, List.of(u1, u2));

    assertThat(out.mergedTasteProfile().ingredientLikes()).isEmpty();
    assertThat(out.mergedLifestyleConfig().mealTimingWindowStart()).isNull();
  }

  @Test
  void merge_equalPriority_meanAcrossPresentUsersOnly() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 =
        new SoftPreferenceBundleDto(
            u1,
            new TasteProfileDocument(Map.of("onion", new BigDecimal("0.5")), Map.of(), List.of()),
            null);
    SoftPreferenceBundleDto b2 =
        new SoftPreferenceBundleDto(
            u2,
            new TasteProfileDocument(Map.of("onion", new BigDecimal("-0.3")), Map.of(), List.of()),
            null);

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 100), null, List.of(u1, u2));

    // (0.5*100 + -0.3*100) / 200 = 0.1
    assertThat(out.mergedTasteProfile().ingredientLikes().get("onion").doubleValue())
        .isEqualTo(0.1d, org.assertj.core.data.Offset.offset(0.001d));
  }

  @Test
  void merge_differingPriority_weightedMean() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 =
        new SoftPreferenceBundleDto(
            u1,
            new TasteProfileDocument(Map.of("onion", new BigDecimal("0.5")), Map.of(), List.of()),
            null);
    SoftPreferenceBundleDto b2 =
        new SoftPreferenceBundleDto(
            u2,
            new TasteProfileDocument(Map.of("onion", new BigDecimal("-0.3")), Map.of(), List.of()),
            null);

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 200), null, List.of(u1, u2));

    // (0.5*100 + -0.3*200) / 300 = -0.0333…
    assertThat(out.mergedTasteProfile().ingredientLikes().get("onion").doubleValue())
        .isEqualTo(-0.0333d, org.assertj.core.data.Offset.offset(0.001d));
  }

  @Test
  void merge_absentUserDoesNotPullMeanTowardZero() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 =
        new SoftPreferenceBundleDto(
            u1,
            new TasteProfileDocument(Map.of("onion", new BigDecimal("0.5")), Map.of(), List.of()),
            null);
    SoftPreferenceBundleDto b2 = new SoftPreferenceBundleDto(u2, null, null);

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 100), null, List.of(u1, u2));

    assertThat(out.mergedTasteProfile().ingredientLikes().get("onion").doubleValue())
        .isEqualTo(0.5d, org.assertj.core.data.Offset.offset(0.0001d));
  }

  @Test
  void merge_avoidList_union_sorted() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 =
        new SoftPreferenceBundleDto(
            u1, new TasteProfileDocument(Map.of(), Map.of(), List.of("onion")), null);
    SoftPreferenceBundleDto b2 =
        new SoftPreferenceBundleDto(
            u2, new TasteProfileDocument(Map.of(), Map.of(), List.of("garlic", "onion")), null);

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 100), null, List.of(u1, u2));

    assertThat(out.mergedTasteProfile().avoidList()).containsExactly("garlic", "onion");
  }

  @Test
  void merge_lifestyle_mostRestrictiveWindow_intersectionNonEmpty() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 =
        new SoftPreferenceBundleDto(
            u1, null, new LifestyleConfigDocument("08:00", "20:00", null, true));
    SoftPreferenceBundleDto b2 =
        new SoftPreferenceBundleDto(
            u2, null, new LifestyleConfigDocument("09:30", "19:00", null, true));

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 100), null, List.of(u1, u2));

    assertThat(out.mergedLifestyleConfig().mealTimingWindowStart()).isEqualTo("09:30");
    assertThat(out.mergedLifestyleConfig().mealTimingWindowEnd()).isEqualTo("19:00");
    assertThat(out.mergedTasteProfile().avoidList()).doesNotContain("WINDOW_INTERSECTION_EMPTY");
  }

  @Test
  void merge_lifestyle_emptyIntersection_pushesWarning() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 =
        new SoftPreferenceBundleDto(
            u1, null, new LifestyleConfigDocument("08:00", "12:00", null, false));
    SoftPreferenceBundleDto b2 =
        new SoftPreferenceBundleDto(
            u2, null, new LifestyleConfigDocument("13:00", "17:00", null, false));

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 100), null, List.of(u1, u2));

    assertThat(out.mergedLifestyleConfig().mealTimingWindowStart()).isEqualTo("13:00");
    assertThat(out.mergedLifestyleConfig().mealTimingWindowEnd()).isEqualTo("12:00");
    assertThat(out.mergedTasteProfile().avoidList()).contains("WINDOW_INTERSECTION_EMPTY");
  }

  @Test
  void merge_noveltyTolerance_minOfNonNull() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    UUID u3 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 =
        new SoftPreferenceBundleDto(u1, null, new LifestyleConfigDocument(null, null, 50, false));
    SoftPreferenceBundleDto b2 =
        new SoftPreferenceBundleDto(u2, null, new LifestyleConfigDocument(null, null, 30, false));
    SoftPreferenceBundleDto b3 =
        new SoftPreferenceBundleDto(u3, null, new LifestyleConfigDocument(null, null, null, false));

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2, b3), List.of(100, 100, 100), null, List.of(u1, u2, u3));

    assertThat(out.mergedLifestyleConfig().noveltyTolerancePercent()).isEqualTo(30);
  }

  @Test
  void merge_batchCookingPreferred_ANDed() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    UUID u3 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 =
        new SoftPreferenceBundleDto(u1, null, new LifestyleConfigDocument(null, null, null, true));
    SoftPreferenceBundleDto b2 =
        new SoftPreferenceBundleDto(u2, null, new LifestyleConfigDocument(null, null, null, true));
    SoftPreferenceBundleDto b3 =
        new SoftPreferenceBundleDto(u3, null, new LifestyleConfigDocument(null, null, null, false));

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2, b3), List.of(100, 100, 100), null, List.of(u1, u2, u3));

    assertThat(out.mergedLifestyleConfig().batchCookingPreferred()).isFalse();
  }

  @Test
  void merge_userIdsByPriority_descendingWithUuidTieBreak() {
    UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID u3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    MergedSoftPreferencesDto out =
        merger.merge(List.of(), List.of(100, 200, 100), null, List.of(u1, u2, u3));

    // priorities: u1=100, u2=200, u3=100 → DESC by priority then ASC by UUID
    assertThat(out.userIdsByPriority()).containsExactly(u2, u1, u3);
  }

  @Test
  void merge_singleUser_degenerate() {
    UUID u1 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 =
        new SoftPreferenceBundleDto(
            u1,
            new TasteProfileDocument(
                Map.of("onion", new BigDecimal("0.7")),
                Map.of("italian", new BigDecimal("0.4")),
                List.of("nuts")),
            new LifestyleConfigDocument("07:00", "21:00", 70, true));

    MergedSoftPreferencesDto out = merger.merge(List.of(b1), List.of(100), null, List.of(u1));

    assertThat(out.mergedTasteProfile().ingredientLikes().get("onion").doubleValue())
        .isEqualTo(0.7d, org.assertj.core.data.Offset.offset(0.0001d));
    assertThat(out.mergedTasteProfile().cuisineLikes().get("italian").doubleValue())
        .isEqualTo(0.4d, org.assertj.core.data.Offset.offset(0.0001d));
    assertThat(out.mergedTasteProfile().avoidList()).containsExactly("nuts");
    assertThat(out.mergedLifestyleConfig().mealTimingWindowStart()).isEqualTo("07:00");
    assertThat(out.mergedLifestyleConfig().mealTimingWindowEnd()).isEqualTo("21:00");
    assertThat(out.mergedLifestyleConfig().noveltyTolerancePercent()).isEqualTo(70);
    assertThat(out.mergedLifestyleConfig().batchCookingPreferred()).isTrue();
  }

  // ---------------- targeted boundary tests (kill survived comparison mutants) ----------------

  private static SoftPreferenceBundleDto lifestyleBundle(
      UUID userId, String start, String end, Integer novelty, boolean batch) {
    return new SoftPreferenceBundleDto(
        userId, null, new LifestyleConfigDocument(start, end, novelty, batch));
  }

  /**
   * Two users; user2's window-start is strictly LATER than user1's. The merged start must be the
   * later one. Kills the {@code compareTo(...) > 0} relational mutant on line 142 (any flip leaves
   * the earlier 06:00 in place).
   */
  @Test
  void mergeLifestyle_picksStrictlyLatestWindowStart() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 = lifestyleBundle(u1, "06:00", "22:00", 80, true);
    SoftPreferenceBundleDto b2 = lifestyleBundle(u2, "09:00", "22:00", 80, true);

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 100), null, List.of(u1, u2));

    assertThat(out.mergedLifestyleConfig().mealTimingWindowStart()).isEqualTo("09:00");
  }

  /**
   * Equal starts must NOT be replaced (the condition is strict {@code >}, not {@code >=}). Order
   * the later-equal bundle second so a {@code >=} mutant would still keep "08:00" — but a
   * conditionals-boundary mutant that drops the comparison entirely would overwrite. Combined with
   * the strict-latest test above this pins the operator exactly.
   */
  @Test
  void mergeLifestyle_equalWindowStartIsStable() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 = lifestyleBundle(u1, "08:00", "20:00", 50, true);
    SoftPreferenceBundleDto b2 = lifestyleBundle(u2, "08:00", "20:00", 50, true);

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 100), null, List.of(u1, u2));

    assertThat(out.mergedLifestyleConfig().mealTimingWindowStart()).isEqualTo("08:00");
    assertThat(out.mergedLifestyleConfig().mealTimingWindowEnd()).isEqualTo("20:00");
  }

  /**
   * user2's window-end is strictly EARLIER than user1's. Merged end must be the earlier one. Kills
   * the {@code compareTo(...) < 0} relational mutant on line 146.
   */
  @Test
  void mergeLifestyle_picksStrictlyEarliestWindowEnd() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 = lifestyleBundle(u1, "06:00", "23:00", 80, true);
    SoftPreferenceBundleDto b2 = lifestyleBundle(u2, "06:00", "19:00", 80, true);

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 100), null, List.of(u1, u2));

    assertThat(out.mergedLifestyleConfig().mealTimingWindowEnd()).isEqualTo("19:00");
  }

  /**
   * user2's novelty tolerance is strictly LOWER than user1's. Merged novelty is the minimum. Kills
   * the {@code noveltyTolerancePercent() < minNovelty} relational mutant on line 150.
   */
  @Test
  void mergeLifestyle_picksMinimumNoveltyTolerance() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 = lifestyleBundle(u1, "06:00", "22:00", 90, true);
    SoftPreferenceBundleDto b2 = lifestyleBundle(u2, "06:00", "22:00", 30, true);

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 100), null, List.of(u1, u2));

    assertThat(out.mergedLifestyleConfig().noveltyTolerancePercent()).isEqualTo(30);
  }

  /**
   * Bundles carry a taste profile but NO lifestyle config (so {@code allEmpty} is false and {@code
   * mergeLifestyle} is invoked, but {@code anyLifestyle} stays false). The early-return on line
   * 157-158 must yield the empty lifestyle config. Kills the {@code if (!anyLifestyle)}
   * negation/removal mutant — without the guard a NullPointer / non-empty config would surface.
   */
  @Test
  void mergeLifestyle_tasteOnlyBundles_returnsEmptyLifestyleViaAnyLifestyleGuard() {
    UUID u1 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 =
        new SoftPreferenceBundleDto(
            u1,
            new TasteProfileDocument(
                Map.of("garlic", new BigDecimal("0.9")), Map.of(), List.of("nuts")),
            null);

    MergedSoftPreferencesDto out = merger.merge(List.of(b1), List.of(100), null, List.of(u1));

    assertThat(out.mergedTasteProfile().ingredientLikes()).containsKey("garlic");
    assertThat(out.mergedLifestyleConfig().mealTimingWindowStart()).isNull();
    assertThat(out.mergedLifestyleConfig().mealTimingWindowEnd()).isNull();
    assertThat(out.mergedLifestyleConfig().noveltyTolerancePercent()).isNull();
    assertThat(out.mergedLifestyleConfig().batchCookingPreferred()).isFalse();
    // avoid-list must NOT have gained the window-intersection warning.
    assertThat(out.mergedTasteProfile().avoidList()).containsExactly("nuts");
  }

  /**
   * latestStart ("20:00") is strictly AFTER earliestEnd ("08:00") so the windows cannot intersect:
   * the {@code WINDOW_INTERSECTION_EMPTY} sentinel must be appended to the avoid list. Kills the
   * {@code latestStart.compareTo(earliestEnd) > 0} relational mutant on line 162.
   */
  @Test
  void mergeLifestyle_nonIntersectingWindows_appendWindowIntersectionEmptySentinel() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    // u1 wants a late start; u2 wants an early end -> latestStart 20:00 > earliestEnd 08:00.
    SoftPreferenceBundleDto b1 = lifestyleBundle(u1, "20:00", "23:00", 80, true);
    SoftPreferenceBundleDto b2 = lifestyleBundle(u2, "05:00", "08:00", 80, true);

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 100), null, List.of(u1, u2));

    assertThat(out.mergedTasteProfile().avoidList()).contains("WINDOW_INTERSECTION_EMPTY");
  }

  /**
   * Intersecting windows (start 07:00 <= end 21:00) must NOT add the sentinel — pins the other side
   * of the line-162 boundary.
   */
  @Test
  void mergeLifestyle_intersectingWindows_noSentinel() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    SoftPreferenceBundleDto b1 = lifestyleBundle(u1, "07:00", "21:00", 80, true);
    SoftPreferenceBundleDto b2 = lifestyleBundle(u2, "06:00", "22:00", 80, true);

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 100), null, List.of(u1, u2));

    assertThat(out.mergedTasteProfile().avoidList()).doesNotContain("WINDOW_INTERSECTION_EMPTY");
  }

  /**
   * Equal priorities force the {@code sortByPriorityDesc} tie-breaker to fall to {@code
   * userIds.get(a).compareTo(userIds.get(b))} (line 184). Two fixed UUIDs with a known ordering
   * must come out ascending-by-UUID. Kills the comparator-return mutation on line 184.
   */
  @Test
  void sortByPriorityDesc_equalPriorities_tieBreaksByUuidAscending() {
    UUID lo = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID hi = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    // Provide them in reverse order so a no-op tie-break would leave [hi, lo].
    MergedSoftPreferencesDto out =
        merger.merge(List.of(), List.of(100, 100), null, List.of(hi, lo));

    // contributingUserIds echoes the raw input; userIdsByPriority is the sorted projection.
    assertThat(out.contributingUserIds()).containsExactly(hi, lo);
    assertThat(out.userIdsByPriority()).containsExactly(lo, hi);
  }

  /**
   * priorities list is SHORTER than the user list, exercising the {@code i >= priorities.size()}
   * fallback-to-100 branch on line 194 (and {@code priorities.get(i) == null}). u1 has explicit
   * priority 250 (rank first); u2 falls through to the 100 default; u3 has an explicit null →
   * default 100, tie-broken after u2 only if u2's UUID sorts first. We use fixed UUIDs to make the
   * ordering deterministic and assert u1 leads. Kills the boundary mutant on the {@code >=}.
   */
  @Test
  void priorityAt_missingAndNullPrioritiesFallBackTo100() {
    UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000010");
    UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000020");
    UUID u3 = UUID.fromString("00000000-0000-0000-0000-000000000030");
    java.util.List<Integer> priorities = new java.util.ArrayList<>();
    priorities.add(250);
    priorities.add(null);
    // index 2 (u3) missing entirely -> i >= priorities.size()

    MergedSoftPreferencesDto out = merger.merge(List.of(), priorities, null, List.of(u1, u2, u3));

    // u1 (250) ranks first; u2 & u3 both default to 100 and tie-break by ascending UUID.
    assertThat(out.userIdsByPriority()).containsExactly(u1, u2, u3);
  }

  /**
   * latestStart EXACTLY EQUALS earliestEnd ("12:00" == "12:00"). The windows touch but do not
   * cross, so {@code latestStart.compareTo(earliestEnd) > 0} is false and NO sentinel is added. The
   * {@code ConditionalsBoundaryMutator} that rewrites {@code > 0} to {@code >= 0} WOULD add the
   * sentinel here (compareTo returns 0) — so this exact-equality fixture is what kills that
   * surviving boundary mutant on line 162 (the earlier strict-greater / strict-less fixtures could
   * not, because compareTo never returned 0 there).
   */
  @Test
  void mergeLifestyle_windowStartEqualsEnd_noSentinel_killsBoundaryMutant() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    // u1 forces latestStart=12:00; u2 forces earliestEnd=12:00 -> equal, compareTo == 0.
    SoftPreferenceBundleDto b1 = lifestyleBundle(u1, "12:00", "23:00", 80, true);
    SoftPreferenceBundleDto b2 = lifestyleBundle(u2, "05:00", "12:00", 80, true);

    MergedSoftPreferencesDto out =
        merger.merge(List.of(b1, b2), List.of(100, 100), null, List.of(u1, u2));

    assertThat(out.mergedLifestyleConfig().mealTimingWindowStart()).isEqualTo("12:00");
    assertThat(out.mergedLifestyleConfig().mealTimingWindowEnd()).isEqualTo("12:00");
    assertThat(out.mergedTasteProfile().avoidList()).doesNotContain("WINDOW_INTERSECTION_EMPTY");
  }

  /**
   * One user has an EXPLICIT priority of 50 (below the 100 default); the other user's priority is
   * missing so {@code priorityAt} returns the 100 default. With the real default (100) the
   * missing-priority user outranks the explicit-50 user. The {@code PrimitiveReturnsMutator} that
   * rewrites {@code return 100} to {@code return 0} would instead rank the explicit-50 user first —
   * so asserting the missing-priority user leads kills that surviving return-value mutant on line
   * 195 (the earlier all-default fixture could not distinguish 100 from 0).
   */
  @Test
  void priorityAt_defaultOneHundredOutranksExplicitlyLowerPriority_killsReturnMutant() {
    UUID explicitLow = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID missing = UUID.fromString("00000000-0000-0000-0000-000000000002");
    java.util.List<Integer> priorities = new java.util.ArrayList<>();
    priorities.add(50); // explicitLow
    // index 1 (missing) absent -> priorityAt returns the 100 default

    MergedSoftPreferencesDto out =
        merger.merge(List.of(), priorities, null, List.of(explicitLow, missing));

    // default 100 > explicit 50 -> missing-priority user ranks first.
    assertThat(out.userIdsByPriority()).containsExactly(missing, explicitLow);
  }
}
