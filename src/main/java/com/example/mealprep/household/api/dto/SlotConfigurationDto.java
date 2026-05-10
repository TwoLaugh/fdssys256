package com.example.mealprep.household.api.dto;

import com.example.mealprep.household.domain.entity.SlotKind;
import java.util.List;
import java.util.UUID;

/**
 * Resolved slot configuration view for a household: one entry per built-in {@code SlotKind} present
 * in {@code slotDefaults} plus one entry per {@code customSlots[]}; per-person slots carry the
 * household's full member list under {@code eaterUserIdsIfPerPerson}; shared slots leave it null.
 */
public record SlotConfigurationDto(
    UUID householdId, List<SlotConfigEntryDto> slots, List<UUID> allEaterUserIds) {

  public record SlotConfigEntryDto(
      String slotKey,
      SlotKind kind,
      boolean shared,
      int headcount,
      int timeBudgetMin,
      List<UUID> eaterUserIdsIfPerPerson) {}
}
