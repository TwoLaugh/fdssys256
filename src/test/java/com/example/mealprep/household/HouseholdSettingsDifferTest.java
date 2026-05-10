package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.household.domain.entity.HouseholdSettingsAuditLog;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.CustomSlotDefinition;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.HouseholdSchedulingPreferences;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.SlotDefault;
import com.example.mealprep.household.domain.entity.SlotKind;
import com.example.mealprep.household.domain.service.internal.HouseholdSettingsDiffer;
import com.example.mealprep.household.testdata.HouseholdTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies the per-section diff in {@link HouseholdSettingsDiffer}: identical documents emit no
 * rows, single-field flips emit exactly one row, and adds/removes from {@code customSlots} key the
 * row by the slot's {@code key}.
 */
class HouseholdSettingsDifferTest {

  private final HouseholdSettingsDiffer differ = new HouseholdSettingsDiffer(new ObjectMapper());
  private final UUID settingsId = UUID.randomUUID();
  private final UUID actorUserId = UUID.randomUUID();

  @Test
  void diff_whenDocumentsIdentical_emitsNoRows() {
    HouseholdSettingsDocument prev = HouseholdTestData.defaultDocument();
    HouseholdSettingsDocument next = HouseholdTestData.defaultDocument();
    Set<String> changed = new LinkedHashSet<>();

    List<HouseholdSettingsAuditLog> rows =
        differ.diff(settingsId, actorUserId, prev, next, changed);

    assertThat(rows).isEmpty();
    assertThat(changed).isEmpty();
  }

  @Test
  void diff_whenSingleNestedFieldFlipped_emitsOneRow() {
    HouseholdSettingsDocument prev = HouseholdTestData.defaultDocument();
    Map<SlotKind, SlotDefault> nextSlots = new LinkedHashMap<>(prev.slotDefaults());
    nextSlots.put(SlotKind.dinner, new SlotDefault(false, 1, 30));
    HouseholdSettingsDocument next =
        new HouseholdSettingsDocument(
            nextSlots, prev.customSlots(), prev.defaultHeadcount(), prev.scheduling());
    Set<String> changed = new LinkedHashSet<>();

    List<HouseholdSettingsAuditLog> rows =
        differ.diff(settingsId, actorUserId, prev, next, changed);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getFieldPath()).isEqualTo("slotDefaults.dinner.shared");
    assertThat(changed).containsExactly("slotDefaults.dinner.shared");
  }

  @Test
  void diff_whenDefaultHeadcountChanges_emitsOneRow() {
    HouseholdSettingsDocument prev = HouseholdTestData.defaultDocument();
    HouseholdSettingsDocument next =
        new HouseholdSettingsDocument(
            prev.slotDefaults(), prev.customSlots(), 4, prev.scheduling());
    Set<String> changed = new LinkedHashSet<>();

    List<HouseholdSettingsAuditLog> rows =
        differ.diff(settingsId, actorUserId, prev, next, changed);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getFieldPath()).isEqualTo("defaultHeadcount");
    assertThat(rows.get(0).getNewValueJson().asInt()).isEqualTo(4);
    assertThat(changed).containsExactly("defaultHeadcount");
  }

  @Test
  void diff_whenCustomSlotAdded_emitsOneRowKeyedByKey() {
    HouseholdSettingsDocument prev = HouseholdTestData.defaultDocument();
    CustomSlotDefinition added =
        new CustomSlotDefinition("late-snack", "Late snack", SlotKind.snack, true, 1, 15);
    HouseholdSettingsDocument next =
        new HouseholdSettingsDocument(
            prev.slotDefaults(), List.of(added), prev.defaultHeadcount(), prev.scheduling());
    Set<String> changed = new LinkedHashSet<>();

    List<HouseholdSettingsAuditLog> rows =
        differ.diff(settingsId, actorUserId, prev, next, changed);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getFieldPath()).isEqualTo("customSlots.late-snack");
    assertThat(rows.get(0).getPreviousValueJson().isNull()).isTrue();
    assertThat(rows.get(0).getNewValueJson().get("key").asText()).isEqualTo("late-snack");
    assertThat(changed).containsExactly("customSlots.late-snack");
  }

  @Test
  void diff_whenCustomSlotRemoved_emitsOneRowWithNullNewValue() {
    CustomSlotDefinition existing =
        new CustomSlotDefinition("late-snack", "Late snack", SlotKind.snack, true, 1, 15);
    HouseholdSettingsDocument prev =
        new HouseholdSettingsDocument(
            HouseholdTestData.defaultDocument().slotDefaults(),
            List.of(existing),
            null,
            new HouseholdSchedulingPreferences());
    HouseholdSettingsDocument next = HouseholdTestData.defaultDocument();
    Set<String> changed = new LinkedHashSet<>();

    List<HouseholdSettingsAuditLog> rows =
        differ.diff(settingsId, actorUserId, prev, next, changed);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getFieldPath()).isEqualTo("customSlots.late-snack");
    assertThat(rows.get(0).getNewValueJson().isNull()).isTrue();
    assertThat(rows.get(0).getPreviousValueJson().get("key").asText()).isEqualTo("late-snack");
    assertThat(changed).containsExactly("customSlots.late-snack");
  }

  @Test
  void diff_whenMultipleNestedFieldsFlipped_emitsOneRowPerField() {
    HouseholdSettingsDocument prev = HouseholdTestData.defaultDocument();
    Map<SlotKind, SlotDefault> nextSlots = new LinkedHashMap<>(prev.slotDefaults());
    nextSlots.put(SlotKind.dinner, new SlotDefault(false, 4, 60));
    HouseholdSettingsDocument next =
        new HouseholdSettingsDocument(
            nextSlots, prev.customSlots(), prev.defaultHeadcount(), prev.scheduling());
    Set<String> changed = new LinkedHashSet<>();

    List<HouseholdSettingsAuditLog> rows =
        differ.diff(settingsId, actorUserId, prev, next, changed);

    assertThat(rows).hasSize(3);
    assertThat(changed)
        .containsExactly(
            "slotDefaults.dinner.shared",
            "slotDefaults.dinner.headcount",
            "slotDefaults.dinner.timeBudgetMin");
  }
}
