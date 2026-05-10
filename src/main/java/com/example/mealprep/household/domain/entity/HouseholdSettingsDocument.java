package com.example.mealprep.household.domain.entity;

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
 */
public record HouseholdSettingsDocument(
    Map<SlotKind, SlotDefault> slotDefaults,
    List<CustomSlotDefinition> customSlots,
    Integer defaultHeadcount,
    HouseholdSchedulingPreferences scheduling) {

  public record SlotDefault(boolean shared, Integer headcount, Integer timeBudgetMin) {}

  public record CustomSlotDefinition(
      String key,
      String label,
      SlotKind backedByKind,
      boolean shared,
      Integer headcount,
      Integer timeBudgetMin) {}

  /** Reserved for v2 per-day overrides; empty marker record in v1. */
  public record HouseholdSchedulingPreferences() {}
}
