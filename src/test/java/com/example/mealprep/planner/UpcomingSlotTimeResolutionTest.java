package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.service.internal.PlannerServiceImpl;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pure unit test for the planner-01m wall-clock meal-time resolution: the three-level coalesce
 * (slot override → lifestyle-config meal-timing → slot-kind default), the range-start parse, and
 * malformed-value tolerance. Exercises the package-private static helpers on {@link
 * PlannerServiceImpl} directly so no Spring context / Postgres is needed; the end-to-end projection
 * is covered by {@code PlanQueryServiceImplTest} and {@code PlansControllerReadIT}.
 */
class UpcomingSlotTimeResolutionTest {

  private static MealSlot slot(SlotKind kind, LocalTime override) {
    return MealSlot.builder().kind(kind).mealTime(override).build();
  }

  private static Map<String, String> timing(String key, String value) {
    Map<String, String> m = new HashMap<>();
    m.put(key, value);
    return m;
  }

  // ---------------- Level 1: stored override wins ----------------

  @Test
  void resolveMealTime_storedOverridePresent_winsOverConfigAndDefault() {
    MealSlot slot = slot(SlotKind.DINNER, LocalTime.of(20, 0));
    // Even with a contradicting lifestyle-config entry, the override wins.
    Map<String, String> config = timing("dinner", "18:30-19:30");

    assertThat(PlannerServiceImpl.resolveMealTime(slot, config)).isEqualTo(LocalTime.of(20, 0));
  }

  // ---------------- Level 2: lifestyle-config fallback ----------------

  @Test
  void resolveMealTime_noOverride_usesLifestyleConfigRangeStart() {
    MealSlot slot = slot(SlotKind.DINNER, null);
    Map<String, String> config = timing("dinner", "18:30-19:30");

    assertThat(PlannerServiceImpl.resolveMealTime(slot, config)).isEqualTo(LocalTime.of(18, 30));
  }

  @Test
  void resolveMealTime_noOverride_bareTimeString_usedAsIs() {
    MealSlot slot = slot(SlotKind.DINNER, null);
    Map<String, String> config = timing("dinner", "19:00");

    assertThat(PlannerServiceImpl.resolveMealTime(slot, config)).isEqualTo(LocalTime.of(19, 0));
  }

  @Test
  void resolveMealTime_customKind_mapsToConfigKeyWhenPresent() {
    MealSlot slot = slot(SlotKind.CUSTOM, null);
    Map<String, String> config = timing("custom", "21:15-22:00");

    assertThat(PlannerServiceImpl.resolveMealTime(slot, config)).isEqualTo(LocalTime.of(21, 15));
  }

  // ---------------- Level 3: slot-kind default floor (no regression) ----------------

  @ParameterizedTest
  @CsvSource({"BREAKFAST,08:00", "LUNCH,12:30", "DINNER,18:00", "SNACK,15:00", "CUSTOM,12:00"})
  void resolveMealTime_noOverride_noConfig_usesSlotKindDefault(SlotKind kind, String expected) {
    MealSlot slot = slot(kind, null);

    assertThat(PlannerServiceImpl.resolveMealTime(slot, Map.of()))
        .isEqualTo(LocalTime.parse(expected));
  }

  @Test
  void resolveMealTime_configHasNoEntryForThisKind_usesSlotKindDefault() {
    MealSlot slot = slot(SlotKind.DINNER, null);
    // Only a breakfast entry — DINNER falls through to its default 18:00.
    Map<String, String> config = timing("breakfast", "07:00-08:00");

    assertThat(PlannerServiceImpl.resolveMealTime(slot, config)).isEqualTo(LocalTime.of(18, 0));
  }

  @Test
  void resolveMealTime_brunchKeyWithNoMatchingSlotKind_usesSlotKindDefault() {
    // A "brunch" config key has no matching SlotKind; a CUSTOM-kind slot still resolves to the
    // CUSTOM default (documented limitation — brunch slots are CUSTOM-kind in practice).
    MealSlot slot = slot(SlotKind.CUSTOM, null);
    Map<String, String> config = timing("brunch", "10:30-11:30");

    assertThat(PlannerServiceImpl.resolveMealTime(slot, config)).isEqualTo(LocalTime.of(12, 0));
  }

  // ---------------- Malformed values never throw ----------------

  @Test
  void resolveMealTime_malformedConfigValue_fallsThroughToSlotKindDefault() {
    MealSlot slot = slot(SlotKind.DINNER, null);
    Map<String, String> config = timing("dinner", "evening");

    assertThat(PlannerServiceImpl.resolveMealTime(slot, config)).isEqualTo(LocalTime.of(18, 0));
  }

  // ---------------- parseRangeStart unit cases ----------------

  @Test
  void parseRangeStart_range_returnsStart() {
    assertThat(PlannerServiceImpl.parseRangeStart("18:30-19:30")).isEqualTo(LocalTime.of(18, 30));
  }

  @Test
  void parseRangeStart_bareTime_returnsTime() {
    assertThat(PlannerServiceImpl.parseRangeStart("07:05")).isEqualTo(LocalTime.of(7, 5));
  }

  @Test
  void parseRangeStart_rangeWithSpaces_isTrimmed() {
    assertThat(PlannerServiceImpl.parseRangeStart("  18:30 - 19:30 "))
        .isEqualTo(LocalTime.of(18, 30));
  }

  @Test
  void parseRangeStart_nullOrBlank_returnsNull() {
    assertThat(PlannerServiceImpl.parseRangeStart(null)).isNull();
    assertThat(PlannerServiceImpl.parseRangeStart("   ")).isNull();
  }

  @Test
  void parseRangeStart_malformed_returnsNull_andDoesNotThrow() {
    assertThat(PlannerServiceImpl.parseRangeStart("evening")).isNull();
    assertThat(PlannerServiceImpl.parseRangeStart("25:99")).isNull();
  }

  @Test
  void kindKey_lowercasesEnumName() {
    assertThat(PlannerServiceImpl.kindKey(SlotKind.BREAKFAST)).isEqualTo("breakfast");
    assertThat(PlannerServiceImpl.kindKey(SlotKind.CUSTOM)).isEqualTo("custom");
  }
}
