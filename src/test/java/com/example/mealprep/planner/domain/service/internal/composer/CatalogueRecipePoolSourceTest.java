package com.example.mealprep.planner.domain.service.internal.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-unit tests over {@link CatalogueRecipePoolSource}: household&rarr;member resolution, the
 * USER&cup;SYSTEM union semantics (de-dup by recipe id across members), the kind-driven candidate
 * limit, and the empty-roster / missing-household degradation paths. The two cross-module read
 * surfaces ({@link HouseholdQueryService}, {@link RecipeQueryService}) belong to other modules so
 * they are legitimately Mockito-mocked.
 */
@ExtendWith(MockitoExtension.class)
class CatalogueRecipePoolSourceTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 5, 18);

  @Mock private HouseholdQueryService householdQueryService;
  @Mock private RecipeQueryService recipeQueryService;

  private CatalogueRecipePoolSource source;

  @BeforeEach
  void setUp() {
    source = new CatalogueRecipePoolSource(householdQueryService, recipeQueryService);
  }

  private HouseholdMemberDto member(UUID householdId, UUID userId) {
    return new HouseholdMemberDto(
        UUID.randomUUID(),
        householdId,
        userId,
        HouseholdRole.primary,
        "member",
        0,
        Instant.parse("2026-01-01T00:00:00Z"),
        0L);
  }

  private HouseholdDto household(UUID householdId, List<HouseholdMemberDto> members) {
    return new HouseholdDto(
        householdId, "h", UUID.randomUUID(), members, Instant.parse("2026-01-01T00:00:00Z"), 0L);
  }

  private MealSlotSkeleton skeleton(SlotKind kind) {
    return PlanTestData.skeletonFor(WEEK, 0, kind, 30);
  }

  // ---------------- happy path ----------------

  @Test
  void fetchPool_singleMember_returnsThatUsersCandidates() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, userId)))));
    RecipeDto r1 =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.DINNER, 30, List.of(), List.of());
    RecipeDto r2 =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.DINNER, 25, List.of(), List.of());
    when(recipeQueryService.findPlannableCandidates(eq(userId), anyInt()))
        .thenReturn(List.of(r1, r2));

    List<RecipeDto> pool =
        source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    assertThat(pool).containsExactly(r1, r2);
    verify(recipeQueryService, times(1)).findPlannableCandidates(eq(userId), anyInt());
  }

  @Test
  void fetchPool_multiMember_unionsAndDeDupsByRecipeId() {
    UUID household = UUID.randomUUID();
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(
            Optional.of(
                household(household, List.of(member(household, userA), member(household, userB)))));

    // Shared SYSTEM recipe appears for BOTH members (same id) — must be de-duped.
    RecipeDto systemShared =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.DINNER, 30, List.of(), List.of());
    RecipeDto userAOnly =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.LUNCH, 20, List.of(), List.of());
    RecipeDto userBOnly =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.BREAKFAST, 15, List.of(), List.of());

    when(recipeQueryService.findPlannableCandidates(eq(userA), anyInt()))
        .thenReturn(List.of(userAOnly, systemShared));
    when(recipeQueryService.findPlannableCandidates(eq(userB), anyInt()))
        .thenReturn(List.of(userBOnly, systemShared));

    List<RecipeDto> pool =
        source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    // 3 distinct recipes, systemShared counted once; insertion order is userA's list then userB's.
    assertThat(pool).containsExactly(userAOnly, systemShared, userBOnly);
    assertThat(pool).hasSize(3);
  }

  @Test
  void fetchPool_duplicateMemberUserIds_queriedOnce() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    // Two member rows for the SAME user-id (defensive: e.g. a roster glitch) -> dedup to one query.
    when(householdQueryService.getById(household))
        .thenReturn(
            Optional.of(
                household(
                    household, List.of(member(household, userId), member(household, userId)))));
    when(recipeQueryService.findPlannableCandidates(eq(userId), anyInt())).thenReturn(List.of());

    source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    verify(recipeQueryService, times(1)).findPlannableCandidates(eq(userId), anyInt());
  }

  @Test
  void fetchPool_skipsNullCandidatesAndNullIds() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, userId)))));
    RecipeDto good =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.DINNER, 30, List.of(), List.of());
    List<RecipeDto> withNulls = new ArrayList<>();
    withNulls.add(null);
    withNulls.add(good);
    when(recipeQueryService.findPlannableCandidates(eq(userId), anyInt())).thenReturn(withNulls);

    List<RecipeDto> pool =
        source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    assertThat(pool).containsExactly(good);
  }

  // ---------------- candidate-limit sizing ----------------

  @Test
  void fetchPool_limitScalesWithDistinctSlotKinds() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, userId)))));
    when(recipeQueryService.findPlannableCandidates(eq(userId), anyInt())).thenReturn(List.of());

    // 3 DISTINCT kinds (DINNER repeated to prove de-dup of kinds) -> 3 * 50 = 150.
    List<MealSlotSkeleton> skeletons =
        List.of(
            skeleton(SlotKind.BREAKFAST),
            skeleton(SlotKind.DINNER),
            skeleton(SlotKind.DINNER),
            skeleton(SlotKind.LUNCH));

    source.fetchPool(household, skeletons, UUID.randomUUID());

    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    verify(recipeQueryService).findPlannableCandidates(eq(userId), limit.capture());
    assertThat(limit.getValue()).isEqualTo(150);
  }

  @Test
  void fetchPool_emptySkeletons_stillUsesAtLeastOneKindWorthOfLimit() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, userId)))));
    when(recipeQueryService.findPlannableCandidates(eq(userId), anyInt())).thenReturn(List.of());

    source.fetchPool(household, List.of(), UUID.randomUUID());

    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    verify(recipeQueryService).findPlannableCandidates(eq(userId), limit.capture());
    assertThat(limit.getValue()).isEqualTo(50); // max(1 kind) * 50
  }

  @Test
  void fetchPool_nullSkeletons_doesNotThrow_usesMinimumLimit() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, userId)))));
    when(recipeQueryService.findPlannableCandidates(eq(userId), anyInt())).thenReturn(List.of());

    source.fetchPool(household, null, UUID.randomUUID());

    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    verify(recipeQueryService).findPlannableCandidates(eq(userId), limit.capture());
    assertThat(limit.getValue()).isEqualTo(50);
  }

  // ---------------- degradation paths ----------------

  @Test
  void fetchPool_householdMissing_returnsEmptyPool_noRecipeRead() {
    UUID household = UUID.randomUUID();
    when(householdQueryService.getById(household)).thenReturn(Optional.empty());

    List<RecipeDto> pool =
        source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    assertThat(pool).isEmpty();
    verifyNoInteractions(recipeQueryService);
  }

  @Test
  void fetchPool_householdWithNoMembers_returnsEmptyPool_noRecipeRead() {
    UUID household = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of())));

    List<RecipeDto> pool =
        source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    assertThat(pool).isEmpty();
    verifyNoInteractions(recipeQueryService);
  }

  @Test
  void fetchPool_memberWithNullUserId_skipped() {
    UUID household = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, null)))));

    List<RecipeDto> pool =
        source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    assertThat(pool).isEmpty();
    verify(recipeQueryService, never()).findPlannableCandidates(eq(null), anyInt());
    verifyNoInteractions(recipeQueryService);
  }

  @Test
  void fetchPool_nullHouseholdId_returnsEmptyPool() {
    List<RecipeDto> pool =
        source.fetchPool(null, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    assertThat(pool).isEmpty();
    verifyNoInteractions(householdQueryService);
    verifyNoInteractions(recipeQueryService);
  }
}
