package com.example.mealprep.planner.domain.service.internal.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.household.api.dto.PlannerSlotEntryDto;
import com.example.mealprep.household.api.dto.SlotConfigurationPlannerViewDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionForPlannerService;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-unit tests over {@link PlanCompositionContextBuilder}. The cross-module read surfaces are
 * legitimately Mockito-mocked (they belong to other modules); {@link RecipePoolSource} is a
 * planner-internal SPI so a real in-test fake is used (playbook: never mock within the module under
 * test). A frozen {@link Clock} pins the snapshot instant so the time-stamping is asserted.
 */
@ExtendWith(MockitoExtension.class)
class PlanCompositionContextBuilderTest {

  private static final Instant NOW = Instant.parse("2026-05-18T08:00:00Z");
  private static final LocalDate WEEK = LocalDate.of(2026, 5, 18);

  @Mock private HouseholdQueryService householdQueryService;
  @Mock private PreferenceQueryService preferenceQueryService;
  @Mock private NutritionQueryService nutritionQueryService;
  @Mock private ProvisionForPlannerService provisionForPlannerService;

  /** A controllable real implementation of the planner-internal recipe-pool SPI. */
  private static final class FakeRecipePoolSource implements RecipePoolSource {
    private List<RecipeDto> pool = new ArrayList<>();
    private int calls;

    @Override
    public List<RecipeDto> fetchPool(
        UUID householdId, List<MealSlotSkeleton> skeletons, UUID traceId) {
      calls++;
      return pool;
    }
  }

  private FakeRecipePoolSource recipePoolSource;
  private PlanCompositionContextBuilder builder;

  @BeforeEach
  void setUp() {
    recipePoolSource = new FakeRecipePoolSource();
    builder =
        new PlanCompositionContextBuilder(
            householdQueryService,
            preferenceQueryService,
            nutritionQueryService,
            provisionForPlannerService,
            recipePoolSource,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private SlotConfigurationPlannerViewDto slotConfig(
      List<PlannerSlotEntryDto> slots, List<UUID> allEaters) {
    return new SlotConfigurationPlannerViewDto(
        UUID.randomUUID(), slots, allEaters, allEaters, null, null, NOW);
  }

  private PlannerSlotEntryDto entry(
      String key,
      com.example.mealprep.household.domain.entity.SlotKind kind,
      List<UUID> perPerson) {
    return new PlannerSlotEntryDto(key, kind, perPerson == null, 2, 30, perPerson, null);
  }

  // ---------- build ----------

  @Test
  void build_twoSlots_produces14SkeletonsAcrossSevenDays_withMappedKinds() {
    UUID householdId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    List<PlannerSlotEntryDto> slots =
        List.of(
            entry(
                "breakfast", com.example.mealprep.household.domain.entity.SlotKind.breakfast, null),
            entry("dinner", com.example.mealprep.household.domain.entity.SlotKind.dinner, null));
    when(householdQueryService.getSlotConfigurationPlannerView(householdId))
        .thenReturn(slotConfig(slots, List.of(userId)));
    when(preferenceQueryService.getHardConstraints(userId)).thenReturn(Optional.empty());
    when(nutritionQueryService.getTargets(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getSettings(eq(householdId), any())).thenReturn(Optional.empty());
    when(provisionForPlannerService.getBundle(userId)).thenReturn(null);

    GeneratePlanRequest req = new GeneratePlanRequest(householdId, WEEK, false);
    UUID traceId = UUID.randomUUID();
    PlanCompositionContext ctx = builder.build(req, userId, traceId, null);

    assertThat(ctx.slotSkeletons()).hasSize(14); // 7 days * 2 slots
    assertThat(ctx.slotSkeletons().stream().map(MealSlotSkeleton::kind))
        .containsOnly(SlotKind.BREAKFAST, SlotKind.DINNER);
    // slotIndex resets per day (0,1 repeating).
    assertThat(ctx.slotSkeletons().get(0).slotIndex()).isEqualTo(0);
    assertThat(ctx.slotSkeletons().get(1).slotIndex()).isEqualTo(1);
    assertThat(ctx.weekStartDate()).isEqualTo(WEEK);
    assertThat(ctx.recipePool().generatedAt()).isEqualTo(NOW);
    assertThat(ctx.traceId()).isEqualTo(traceId);
  }

  @Test
  void build_emptySlotConfig_yieldsNoSkeletons_emptyPoolWarningPathExercised() {
    UUID householdId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getSlotConfigurationPlannerView(householdId))
        .thenReturn(slotConfig(List.of(), List.of()));
    when(preferenceQueryService.getHardConstraints(userId)).thenReturn(Optional.empty());
    when(nutritionQueryService.getTargets(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getSettings(eq(householdId), any())).thenReturn(Optional.empty());
    when(provisionForPlannerService.getBundle(userId)).thenReturn(null);

    GeneratePlanRequest req = new GeneratePlanRequest(householdId, WEEK, false);
    PlanCompositionContext ctx = builder.build(req, userId, UUID.randomUUID(), null);

    assertThat(ctx.slotSkeletons()).isEmpty();
    assertThat(ctx.recipePool().recipes()).isEmpty();
    // Fallback member resolution still calls the per-user surfaces exactly once.
    assertThat(recipePoolSource.calls).isEqualTo(1);
  }

  @Test
  void build_nullSlotConfig_returnsEmptySkeletons_doesNotThrow() {
    UUID householdId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(householdQueryService.getSlotConfigurationPlannerView(householdId)).thenReturn(null);
    when(preferenceQueryService.getHardConstraints(userId)).thenReturn(Optional.empty());
    when(nutritionQueryService.getTargets(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getSettings(eq(householdId), any())).thenReturn(Optional.empty());
    when(provisionForPlannerService.getBundle(userId)).thenReturn(null);

    GeneratePlanRequest req = new GeneratePlanRequest(householdId, WEEK, false);
    PlanCompositionContext ctx = builder.build(req, userId, UUID.randomUUID(), null);

    assertThat(ctx.slotSkeletons()).isEmpty();
  }

  @Test
  void build_perPersonSlot_usesEntryEaters_notHouseholdRoster() {
    UUID householdId = UUID.randomUUID();
    UUID requestUser = UUID.randomUUID();
    UUID rosterUser = UUID.randomUUID();
    UUID perPersonEater = UUID.randomUUID();
    List<PlannerSlotEntryDto> slots =
        List.of(
            entry(
                "dinner",
                com.example.mealprep.household.domain.entity.SlotKind.dinner,
                List.of(perPersonEater)));
    when(householdQueryService.getSlotConfigurationPlannerView(householdId))
        .thenReturn(slotConfig(slots, List.of(rosterUser)));
    lenient().when(preferenceQueryService.getHardConstraints(any())).thenReturn(Optional.empty());
    lenient().when(nutritionQueryService.getTargets(any())).thenReturn(Optional.empty());
    when(householdQueryService.getSettings(eq(householdId), any())).thenReturn(Optional.empty());
    when(provisionForPlannerService.getBundle(requestUser)).thenReturn(null);

    GeneratePlanRequest req = new GeneratePlanRequest(householdId, WEEK, false);
    PlanCompositionContext ctx = builder.build(req, requestUser, UUID.randomUUID(), null);

    // Per-person slot carries exactly its own eater list, not the household roster.
    assertThat(ctx.slotSkeletons().get(0).eaters()).containsExactly(perPersonEater);
  }

  @Test
  void build_defaultKindUsedWhenEntryKindNull() {
    UUID householdId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    List<PlannerSlotEntryDto> slots =
        new ArrayList<>(List.of(new PlannerSlotEntryDto("x", null, true, 2, 30, null, null)));
    when(householdQueryService.getSlotConfigurationPlannerView(householdId))
        .thenReturn(slotConfig(slots, List.of(userId)));
    when(preferenceQueryService.getHardConstraints(userId)).thenReturn(Optional.empty());
    when(nutritionQueryService.getTargets(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getSettings(eq(householdId), any())).thenReturn(Optional.empty());
    when(provisionForPlannerService.getBundle(userId)).thenReturn(null);

    GeneratePlanRequest req = new GeneratePlanRequest(householdId, WEEK, false);
    PlanCompositionContext ctx = builder.build(req, userId, UUID.randomUUID(), null);

    // mapKind(null) -> DINNER per the documented default.
    assertThat(ctx.slotSkeletons()).allMatch(s -> s.kind() == SlotKind.DINNER);
  }

  // ---------- buildForReopt ----------

  @Test
  void buildForReopt_narrowsToNonPinnedSlots_carriesPinnedAssignments_andMintimalContext() {
    Plan active = PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 2, PlanStatus.ACTIVE, 1, 2);
    List<MealSlot> nonPinned = active.getDays().get(0).getSlots();
    for (MealSlot s : nonPinned) {
      // wire the back-pointer chain the builder reads (day + onDate + eaters)
      assertThat(s.getDay()).isNotNull();
    }
    SlotAssignment pinned =
        PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2);
    lenient().when(preferenceQueryService.getHardConstraints(any())).thenReturn(Optional.empty());
    lenient().when(nutritionQueryService.getTargets(any())).thenReturn(Optional.empty());
    UUID traceId = UUID.randomUUID();

    PlanCompositionContext ctx = builder.buildForReopt(active, nonPinned, List.of(pinned), traceId);

    assertThat(ctx.slotSkeletons()).hasSize(nonPinned.size());
    assertThat(ctx.pinnedAssignments()).containsExactly(pinned);
    assertThat(ctx.traceId()).isEqualTo(traceId);
    assertThat(ctx.householdId()).isEqualTo(active.getHouseholdId());
    assertThat(ctx.weekStartDate()).isEqualTo(active.getWeekStartDate());
    // Re-opt path deliberately leaves household settings + provisions null.
    assertThat(ctx.householdSettings()).isNull();
    assertThat(ctx.provisions()).isNull();
    assertThat(ctx.recipePool().generatedAt()).isEqualTo(NOW);
  }

  @Test
  void buildForReopt_nullHardConstraintAndTargets_removedFromMaps() {
    Plan active = PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.ACTIVE, 1, 1);
    List<MealSlot> nonPinned = active.getDays().get(0).getSlots();
    when(preferenceQueryService.getHardConstraints(any())).thenReturn(Optional.empty());
    when(nutritionQueryService.getTargets(any())).thenReturn(Optional.empty());

    PlanCompositionContext ctx =
        builder.buildForReopt(active, nonPinned, List.of(), UUID.randomUUID());

    // Null entries are stripped — the maps end empty rather than carrying null values.
    assertThat(ctx.hardConstraintsByUserId()).isEmpty();
    assertThat(ctx.nutritionByUserId()).isEmpty();
  }
}
