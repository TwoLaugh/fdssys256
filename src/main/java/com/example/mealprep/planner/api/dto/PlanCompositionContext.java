package com.example.mealprep.planner.api.dto;

import com.example.mealprep.household.api.dto.HouseholdSettingsDto;
import com.example.mealprep.household.api.dto.MergedSoftPreferencesDto;
import com.example.mealprep.household.api.dto.SoftPreferenceBundleDto;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only bundle the Stage-A beam search consumes. Per LLD §Read pattern §{@code
 * PlanCompositionContext} (lines 1167-1183); 01d ships a partial — only the fields needed by the
 * search algorithm and hard-filter runner. Deferred fields ({@code nutritionByUserId}, {@code
 * lifestyle}, {@code ingredientPriceConfidenceByMappingKey}, {@code household}) land with 01e / 01f
 * / 01g / 01j when the composer wires the full bundle.
 *
 * <p>The record is constructed by the composer (01j) — 01d's tests build instances directly.
 *
 * <p>{@code pinnedAssignments} is empty for fresh generation; the re-opt path (planner-01i)
 * populates it from {@code PinningRules}.
 */
public record PlanCompositionContext(
    UUID householdId,
    LocalDate weekStartDate,
    List<MealSlotSkeleton> slotSkeletons,
    Map<UUID, HardConstraintsDto> hardConstraintsByUserId,
    Map<UUID, SoftPreferenceBundleDto> softPrefsByUserId,
    MergedSoftPreferencesDto mergedHouseholdPrefs,
    ProvisionForPlannerBundleDto provisions,
    HouseholdSettingsDto householdSettings,
    RecipePoolSnapshot recipePool,
    List<SlotAssignment> pinnedAssignments,
    UUID traceId,
    UUID decisionId) {}
