package com.example.mealprep.planner.domain.service.internal.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.example.mealprep.preference.PreferenceModule;
import com.example.mealprep.preference.domain.service.TasteSimilarityQueryService;
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
 * per-kind taste-ranked candidate reads, the USER&cup;SYSTEM union semantics (de-dup by recipe id
 * across members + kinds), the cold-start (no taste vector) fallback, the CUSTOM-only flat
 * fallback, and the empty-roster / missing-household degradation paths. The three cross-module read
 * surfaces ({@link HouseholdQueryService}, {@link RecipeQueryService}, {@link PreferenceModule})
 * belong to other modules so they are legitimately Mockito-mocked.
 */
@ExtendWith(MockitoExtension.class)
class CatalogueRecipePoolSourceTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 5, 18);
  private static final int CANDIDATES_PER_KIND = 150;
  private static final int MAX_POOL_SIZE = 750;
  private static final String VEC = "[0.1,0.2,0.3]";

  @Mock private HouseholdQueryService householdQueryService;
  @Mock private RecipeQueryService recipeQueryService;
  @Mock private PreferenceModule preferenceModule;
  @Mock private TasteSimilarityQueryService tasteSimilarity;

  private CatalogueRecipePoolSource source;

  @BeforeEach
  void setUp() {
    source = new CatalogueRecipePoolSource(householdQueryService, recipeQueryService, preferenceModule);
  }

  /** Wire the preference facade so {@code userId} has (or lacks) an embedded taste vector. */
  private void stubTaste(UUID userId, String literal) {
    when(preferenceModule.tasteSimilarity()).thenReturn(tasteSimilarity);
    when(tasteSimilarity.getTasteVectorLiteral(userId)).thenReturn(Optional.ofNullable(literal));
  }

  private HouseholdMemberDto member(UUID householdId, UUID userId) {
    return new HouseholdMemberDto(
        UUID.randomUUID(),
        householdId,
        userId,
        HouseholdRole.primary,
        "member",
        null, // username (auth join; not exercised here)
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

  // ---------------- per-kind taste-ranked happy path ----------------

  @Test
  void fetchPool_singleMember_perKind_passesTasteVectorAndKind() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, userId)))));
    stubTaste(userId, VEC);
    RecipeDto r1 =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.DINNER, 30, List.of(), List.of());
    RecipeDto r2 =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.DINNER, 25, List.of(), List.of());
    when(recipeQueryService.findPlannableCandidatesByKind(userId, "dinner", CANDIDATES_PER_KIND, VEC))
        .thenReturn(List.of(r1, r2));

    List<RecipeDto> pool =
        source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    assertThat(pool).containsExactly(r1, r2);
    verify(recipeQueryService, times(1))
        .findPlannableCandidatesByKind(userId, "dinner", CANDIDATES_PER_KIND, VEC);
    // Legacy flat read must not be used on the per-kind path.
    verify(recipeQueryService, never()).findPlannableCandidates(any(), anyInt());
  }

  @Test
  void fetchPool_oneReadPerDistinctSlotKind_lowerCaseMealType() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, userId)))));
    stubTaste(userId, VEC);
    when(recipeQueryService.findPlannableCandidatesByKind(eq(userId), any(), anyInt(), eq(VEC)))
        .thenReturn(List.of());

    // DINNER repeated to prove kind de-dup -> 3 distinct reads: breakfast, dinner, lunch.
    List<MealSlotSkeleton> skeletons =
        List.of(
            skeleton(SlotKind.BREAKFAST),
            skeleton(SlotKind.DINNER),
            skeleton(SlotKind.DINNER),
            skeleton(SlotKind.LUNCH));

    source.fetchPool(household, skeletons, UUID.randomUUID());

    ArgumentCaptor<String> mealType = ArgumentCaptor.forClass(String.class);
    verify(recipeQueryService, times(3))
        .findPlannableCandidatesByKind(eq(userId), mealType.capture(), eq(CANDIDATES_PER_KIND), eq(VEC));
    assertThat(mealType.getAllValues()).containsExactlyInAnyOrder("breakfast", "dinner", "lunch");
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
    when(preferenceModule.tasteSimilarity()).thenReturn(tasteSimilarity);
    when(tasteSimilarity.getTasteVectorLiteral(userA)).thenReturn(Optional.of(VEC));
    when(tasteSimilarity.getTasteVectorLiteral(userB)).thenReturn(Optional.of(VEC));

    // Shared SYSTEM recipe appears for BOTH members (same id) — must be de-duped.
    RecipeDto systemShared =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.DINNER, 30, List.of(), List.of());
    RecipeDto userAOnly =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.DINNER, 20, List.of(), List.of());
    RecipeDto userBOnly =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.DINNER, 15, List.of(), List.of());

    when(recipeQueryService.findPlannableCandidatesByKind(userA, "dinner", CANDIDATES_PER_KIND, VEC))
        .thenReturn(List.of(userAOnly, systemShared));
    when(recipeQueryService.findPlannableCandidatesByKind(userB, "dinner", CANDIDATES_PER_KIND, VEC))
        .thenReturn(List.of(userBOnly, systemShared));

    List<RecipeDto> pool =
        source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    // 3 distinct recipes, systemShared counted once; insertion order is userA's list then userB's.
    assertThat(pool).containsExactly(userAOnly, systemShared, userBOnly);
  }

  @Test
  void fetchPool_duplicateMemberUserIds_queriedOnce() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    // Two member rows for the SAME user-id (defensive: e.g. a roster glitch) -> dedup to one user.
    when(householdQueryService.getById(household))
        .thenReturn(
            Optional.of(
                household(
                    household, List.of(member(household, userId), member(household, userId)))));
    stubTaste(userId, VEC);
    when(recipeQueryService.findPlannableCandidatesByKind(eq(userId), any(), anyInt(), any()))
        .thenReturn(List.of());

    source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    verify(recipeQueryService, times(1))
        .findPlannableCandidatesByKind(eq(userId), eq("dinner"), anyInt(), any());
  }

  @Test
  void fetchPool_skipsNullCandidatesAndNullIds() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, userId)))));
    stubTaste(userId, VEC);
    RecipeDto good =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.DINNER, 30, List.of(), List.of());
    List<RecipeDto> withNulls = new ArrayList<>();
    withNulls.add(null);
    withNulls.add(good);
    when(recipeQueryService.findPlannableCandidatesByKind(eq(userId), eq("dinner"), anyInt(), any()))
        .thenReturn(withNulls);

    List<RecipeDto> pool =
        source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    assertThat(pool).containsExactly(good);
  }

  // ---------------- cold-start (no embedded vector) ----------------

  @Test
  void fetchPool_noTasteVector_passesNullVectorToPerKindRead() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, userId)))));
    stubTaste(userId, null); // no EMBEDDED vector -> Optional.empty()
    when(recipeQueryService.findPlannableCandidatesByKind(
            eq(userId), eq("dinner"), anyInt(), isNull()))
        .thenReturn(List.of());

    source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    // Vector arg is null -> the recipe side uses the createdAt-ordered per-kind fallback.
    verify(recipeQueryService)
        .findPlannableCandidatesByKind(eq(userId), eq("dinner"), eq(CANDIDATES_PER_KIND), isNull());
  }

  // ---------------- CUSTOM-only / no-standard-kind flat fallback ----------------

  @Test
  void fetchPool_customOnly_fallsBackToFlatRead() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, userId)))));
    RecipeDto r =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.DINNER, 30, List.of(), List.of());
    when(recipeQueryService.findPlannableCandidates(userId, MAX_POOL_SIZE)).thenReturn(List.of(r));

    List<RecipeDto> pool =
        source.fetchPool(household, List.of(skeleton(SlotKind.CUSTOM)), UUID.randomUUID());

    assertThat(pool).containsExactly(r);
    verify(recipeQueryService, times(1)).findPlannableCandidates(userId, MAX_POOL_SIZE);
    verify(recipeQueryService, never())
        .findPlannableCandidatesByKind(any(), any(), anyInt(), any());
    // No taste read on the flat fallback.
    verifyNoInteractions(preferenceModule);
  }

  @Test
  void fetchPool_emptySkeletons_fallsBackToFlatRead() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, userId)))));
    when(recipeQueryService.findPlannableCandidates(userId, MAX_POOL_SIZE)).thenReturn(List.of());

    source.fetchPool(household, List.of(), UUID.randomUUID());

    verify(recipeQueryService, times(1)).findPlannableCandidates(userId, MAX_POOL_SIZE);
    verifyNoInteractions(preferenceModule);
  }

  @Test
  void fetchPool_nullSkeletons_doesNotThrow_fallsBackToFlatRead() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, userId)))));
    when(recipeQueryService.findPlannableCandidates(userId, MAX_POOL_SIZE)).thenReturn(List.of());

    source.fetchPool(household, null, UUID.randomUUID());

    verify(recipeQueryService, times(1)).findPlannableCandidates(userId, MAX_POOL_SIZE);
  }

  // ---------------- degradation paths ----------------

  @Test
  void fetchPool_householdMissing_returnsEmptyPool_noReads() {
    UUID household = UUID.randomUUID();
    when(householdQueryService.getById(household)).thenReturn(Optional.empty());

    List<RecipeDto> pool =
        source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    assertThat(pool).isEmpty();
    verifyNoInteractions(recipeQueryService);
    verifyNoInteractions(preferenceModule);
  }

  @Test
  void fetchPool_householdWithNoMembers_returnsEmptyPool_noReads() {
    UUID household = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of())));

    List<RecipeDto> pool =
        source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    assertThat(pool).isEmpty();
    verifyNoInteractions(recipeQueryService);
    verifyNoInteractions(preferenceModule);
  }

  @Test
  void fetchPool_memberWithNullUserId_skipped() {
    UUID household = UUID.randomUUID();
    when(householdQueryService.getById(household))
        .thenReturn(Optional.of(household(household, List.of(member(household, null)))));

    List<RecipeDto> pool =
        source.fetchPool(household, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    assertThat(pool).isEmpty();
    verifyNoInteractions(recipeQueryService);
    verifyNoInteractions(preferenceModule);
  }

  @Test
  void fetchPool_nullHouseholdId_returnsEmptyPool() {
    List<RecipeDto> pool =
        source.fetchPool(null, List.of(skeleton(SlotKind.DINNER)), UUID.randomUUID());

    assertThat(pool).isEmpty();
    verifyNoInteractions(householdQueryService);
    verifyNoInteractions(recipeQueryService);
    verifyNoInteractions(preferenceModule);
  }
}
