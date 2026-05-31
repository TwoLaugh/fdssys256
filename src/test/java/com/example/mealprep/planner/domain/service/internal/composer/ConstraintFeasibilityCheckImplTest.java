package com.example.mealprep.planner.domain.service.internal.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.ConflictType;
import com.example.mealprep.planner.api.dto.FeasibilityCheckResultDto;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.domain.service.internal.beamsearch.HardFilterRunner;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage for {@link ConstraintFeasibilityCheckImpl} (planner-6): pool-size detection,
 * conflict-type classification, and resolution ranking. {@link HardFilterRunner} is mocked so the
 * per-slot pool is controlled directly; {@code minPoolPerSlot} comes from the real default props
 * (3).
 */
@ExtendWith(MockitoExtension.class)
class ConstraintFeasibilityCheckImplTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 6, 15);

  @Mock private HardFilterRunner hardFilterRunner;

  private ConstraintFeasibilityCheckImpl check() {
    return new ConstraintFeasibilityCheckImpl(hardFilterRunner, PlanTestData.scoringProperties());
  }

  private static MealSlotSkeleton slot(
      UUID slotId, SlotKind kind, boolean shared, List<UUID> eaters) {
    return new MealSlotSkeleton(
        UUID.randomUUID(), slotId, 0, WEEK, kind, kind.name(), 60, shared, eaters);
  }

  private static PlanCompositionContext ctx(
      List<MealSlotSkeleton> skeletons, Map<UUID, HardConstraintsDto> hard) {
    return new PlanCompositionContext(
        UUID.randomUUID(),
        WEEK,
        skeletons,
        hard,
        Map.of(),
        null,
        null,
        null,
        null,
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Map.of());
  }

  private static HardConstraintsDto allergyConstraints(UUID userId) {
    return new HardConstraintsDto(
        UUID.randomUUID(), userId, List.of("peanut"), null, List.of(), List.of(), List.of(), 0L);
  }

  @Test
  void emptySkeletons_areTriviallyFeasible() {
    FeasibilityCheckResultDto result = check().check(ctx(List.of(), Map.of()));
    assertThat(result.feasible()).isTrue();
    assertThat(result.conflicts()).isEmpty();
    assertThat(result.resolutions()).isEmpty();
  }

  @Test
  void adequatePool_isFeasible() {
    UUID slotId = UUID.randomUUID();
    MealSlotSkeleton skel = slot(slotId, SlotKind.DINNER, false, List.of(UUID.randomUUID()));
    when(hardFilterRunner.filterPool(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of(slotId, pool(3)));

    FeasibilityCheckResultDto result = check().check(ctx(List.of(skel), Map.of()));

    assertThat(result.feasible()).isTrue();
    assertThat(result.conflicts()).isEmpty();
  }

  @Test
  void underPooledSharedSlotWithTwoConstrainedEaters_classifiesHouseholdHardCollision() {
    UUID slotId = UUID.randomUUID();
    UUID eaterA = UUID.randomUUID();
    UUID eaterB = UUID.randomUUID();
    MealSlotSkeleton skel = slot(slotId, SlotKind.DINNER, true, List.of(eaterA, eaterB));
    when(hardFilterRunner.filterPool(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of(slotId, pool(1))); // below min 3

    Map<UUID, HardConstraintsDto> hard = new LinkedHashMap<>();
    hard.put(eaterA, allergyConstraints(eaterA));
    hard.put(eaterB, allergyConstraints(eaterB));

    FeasibilityCheckResultDto result = check().check(ctx(List.of(skel), hard));

    assertThat(result.feasible()).isFalse();
    assertThat(result.conflicts()).hasSize(1);
    assertThat(result.conflicts().get(0).type()).isEqualTo(ConflictType.HOUSEHOLD_HARD_COLLISION);
    assertThat(result.conflicts().get(0).affectedSlotIds()).containsExactly(slotId);
    // split_slot is the offered resolution.
    assertThat(result.resolutions()).extracting("key").contains("split_slot");
  }

  @Test
  void underPooledPlainSlot_classifiesOverSpecifiedPreferences_withWidenResolution() {
    UUID slotId = UUID.randomUUID();
    MealSlotSkeleton skel = slot(slotId, SlotKind.DINNER, false, List.of(UUID.randomUUID()));
    when(hardFilterRunner.filterPool(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of(slotId, pool(0)));

    FeasibilityCheckResultDto result = check().check(ctx(List.of(skel), Map.of()));

    assertThat(result.feasible()).isFalse();
    assertThat(result.conflicts().get(0).type()).isEqualTo(ConflictType.OVER_SPECIFIED_PREFERENCES);
    assertThat(result.resolutions()).extracting("key").containsExactly("widen_preferences");
  }

  @Test
  void resolutions_areRankedBySlotsRecoveredDescending() {
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();
    UUID s3 = UUID.randomUUID();
    UUID eater = UUID.randomUUID();
    // s1: plain under-pooled (OVER_SPECIFIED, 1 slot). s2+s3: plain under-pooled too → coalesced
    // into one OVER_SPECIFIED conflict covering 2 slots. The 2-slot conflict's resolution must
    // rank ahead of any 1-slot one — here all are OVER_SPECIFIED so they coalesce into a single
    // 3-slot conflict; assert the single resolution reports 3 slots recovered.
    List<MealSlotSkeleton> skeletons =
        List.of(
            slot(s1, SlotKind.DINNER, false, List.of(eater)),
            slot(s2, SlotKind.LUNCH, false, List.of(eater)),
            slot(s3, SlotKind.BREAKFAST, false, List.of(eater)));
    Map<UUID, List<RecipeDto>> pools = new LinkedHashMap<>();
    pools.put(s1, pool(0));
    pools.put(s2, pool(1));
    pools.put(s3, pool(2));
    when(hardFilterRunner.filterPool(org.mockito.ArgumentMatchers.any())).thenReturn(pools);

    FeasibilityCheckResultDto result = check().check(ctx(skeletons, Map.of()));

    assertThat(result.feasible()).isFalse();
    assertThat(result.conflicts()).hasSize(1);
    assertThat(result.conflicts().get(0).affectedSlotIds()).containsExactlyInAnyOrder(s1, s2, s3);
    assertThat(result.resolutions().get(0).slotsRecovered()).isEqualTo(3);
  }

  /** A pool of {@code n} distinct recipes (content irrelevant — only the size matters). */
  private static List<RecipeDto> pool(int n) {
    java.util.List<RecipeDto> out = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(
          PlanTestData.scoredRecipe(
              UUID.randomUUID(), 20, "italian", "chicken", "bake", List.of()));
    }
    return out;
  }
}
