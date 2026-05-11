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
}
