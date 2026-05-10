package com.example.mealprep.household.domain.service.internal;

import com.example.mealprep.household.api.dto.SlotConfigurationDto;
import com.example.mealprep.household.api.dto.SlotConfigurationDto.SlotConfigEntryDto;
import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import com.example.mealprep.household.domain.entity.HouseholdSettings;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.CustomSlotDefinition;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.SlotDefault;
import com.example.mealprep.household.domain.entity.SlotKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Walks a {@link HouseholdSettings} document + the {@link Household}'s member list to produce a
 * resolved {@link SlotConfigurationDto}: one entry per built-in {@link SlotKind} present in {@code
 * slotDefaults} and one entry per element of {@code customSlots}. {@code eaterUserIdsIfPerPerson}
 * is non-null only when {@code shared = false}; it carries every household member's user-id.
 *
 * <p>Read-only; no DB access. The caller resolves {@link Household} + {@link HouseholdSettings} and
 * passes them in.
 */
@Component
public class SlotConfigurationResolver {

  /** Default fallback when a slot has no headcount; mirrors the LLD Flow 1 line 406 baseline. */
  private static final int DEFAULT_HEADCOUNT = 1;

  /** Default fallback when a slot has no time budget. */
  private static final int DEFAULT_TIME_BUDGET_MIN = 30;

  public SlotConfigurationDto resolve(Household household, HouseholdSettings settings) {
    UUID householdId = household.getId();
    List<UUID> allEaterUserIds = collectMemberUserIds(household);
    HouseholdSettingsDocument doc = settings.getDocument();
    List<SlotConfigEntryDto> slots = new ArrayList<>();

    Map<SlotKind, SlotDefault> slotDefaults = doc.slotDefaults();
    if (slotDefaults != null) {
      for (Map.Entry<SlotKind, SlotDefault> entry : slotDefaults.entrySet()) {
        SlotKind kind = entry.getKey();
        SlotDefault def = entry.getValue();
        if (def == null) {
          continue;
        }
        slots.add(
            new SlotConfigEntryDto(
                kind.name(),
                kind,
                def.shared(),
                def.headcount() == null ? DEFAULT_HEADCOUNT : def.headcount(),
                def.timeBudgetMin() == null ? DEFAULT_TIME_BUDGET_MIN : def.timeBudgetMin(),
                def.shared() ? null : List.copyOf(allEaterUserIds)));
      }
    }

    List<CustomSlotDefinition> customSlots = doc.customSlots();
    if (customSlots != null) {
      for (CustomSlotDefinition custom : customSlots) {
        if (custom == null) {
          continue;
        }
        slots.add(
            new SlotConfigEntryDto(
                custom.key(),
                custom.backedByKind(),
                custom.shared(),
                custom.headcount() == null ? DEFAULT_HEADCOUNT : custom.headcount(),
                custom.timeBudgetMin() == null ? DEFAULT_TIME_BUDGET_MIN : custom.timeBudgetMin(),
                custom.shared() ? null : List.copyOf(allEaterUserIds)));
      }
    }

    return new SlotConfigurationDto(householdId, slots, allEaterUserIds);
  }

  private static List<UUID> collectMemberUserIds(Household household) {
    List<HouseholdMember> members = household.getMembers();
    if (members == null || members.isEmpty()) {
      return Collections.emptyList();
    }
    List<UUID> ids = new ArrayList<>(members.size());
    for (HouseholdMember m : members) {
      if (m != null && m.getUserId() != null) {
        ids.add(m.getUserId());
      }
    }
    return List.copyOf(ids);
  }
}
