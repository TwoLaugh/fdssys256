package com.example.mealprep.household.domain.entity;

import com.example.mealprep.household.validation.ValidHeadcount;
import com.example.mealprep.household.validation.ValidSlotKey;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * JSONB document persisted alongside {@code HouseholdSettings}. Mirrors the OpenAPI {@code
 * HouseholdSettingsDocument} schema; persisted via hypersistence-utils {@code JsonBinaryType}.
 *
 * <p>Top-level fields drive the per-section diff in {@code HouseholdSettingsDiffer}; nested records
 * ({@link SlotDefault}, {@link CustomSlotDefinition}) are diffed key-by-key (slotDefaults) or by
 * business-key {@code key} (customSlots). Nullable values (e.g. {@code defaultHeadcount}, scalar
 * fields on {@code SlotDefault}) survive Jackson serialisation as {@code null}.
 *
 * <p>Custom validation (household-2): {@code @Valid} cascades into the nested records so {@link
 * ValidHeadcount} (per-slot headcount bound) and {@link ValidSlotKey} (custom-slot key format /
 * built-in collision check) are enforced wherever bean-validation runs on the document — chiefly
 * the {@code @Valid} cascade from {@code UpdateHouseholdSettingsRequest}.
 */
public record HouseholdSettingsDocument(
    Map<SlotKind, @Valid SlotDefault> slotDefaults,
    List<@Valid CustomSlotDefinition> customSlots,
    @ValidHeadcount Integer defaultHeadcount,
    HouseholdSchedulingPreferences scheduling) {

  public record SlotDefault(
      boolean shared, @ValidHeadcount Integer headcount, Integer timeBudgetMin) {}

  public record CustomSlotDefinition(
      @ValidSlotKey String key,
      String label,
      SlotKind backedByKind,
      boolean shared,
      @ValidHeadcount Integer headcount,
      Integer timeBudgetMin) {}

  /** Reserved for v2 per-day overrides; empty marker record in v1. */
  public record HouseholdSchedulingPreferences() {}
}
