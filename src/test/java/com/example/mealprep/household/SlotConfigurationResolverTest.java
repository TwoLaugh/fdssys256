package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.household.api.dto.SlotConfigurationDto;
import com.example.mealprep.household.api.dto.SlotConfigurationDto.SlotConfigEntryDto;
import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.entity.HouseholdSettings;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.CustomSlotDefinition;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.HouseholdSchedulingPreferences;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.SlotDefault;
import com.example.mealprep.household.domain.entity.SlotKind;
import com.example.mealprep.household.domain.service.internal.SlotConfigurationResolver;
import com.example.mealprep.household.testdata.HouseholdTestData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies the resolver populates {@code eaterUserIdsIfPerPerson} only for shared=false slots and
 * always returns the full member list under {@code allEaterUserIds}.
 */
class SlotConfigurationResolverTest {

  private final SlotConfigurationResolver resolver = new SlotConfigurationResolver();

  @Test
  void resolve_emitsEntryPerSlotDefault_andOmitsEaterListsForSharedSlots() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();

    HouseholdMember memberA =
        HouseholdTestData.member().withUserId(userA).withRole(HouseholdRole.primary).build();
    HouseholdMember memberB =
        HouseholdTestData.member().withUserId(userB).withRole(HouseholdRole.member).build();
    Household household =
        HouseholdTestData.household().withMember(memberA).withMember(memberB).build();
    HouseholdSettings settings =
        HouseholdSettings.builder()
            .id(UUID.randomUUID())
            .householdId(household.getId())
            .document(HouseholdTestData.defaultDocument())
            .build();

    SlotConfigurationDto result = resolver.resolve(household, settings);

    assertThat(result.householdId()).isEqualTo(household.getId());
    assertThat(result.allEaterUserIds()).containsExactlyInAnyOrder(userA, userB);
    assertThat(result.slots()).hasSize(4);
    for (SlotConfigEntryDto slot : result.slots()) {
      assertThat(slot.shared()).isTrue();
      assertThat(slot.eaterUserIdsIfPerPerson()).isNull();
      assertThat(slot.headcount()).isEqualTo(1);
      assertThat(slot.timeBudgetMin()).isEqualTo(30);
    }
  }

  @Test
  void resolve_populatesEaterListsForPerPersonSlots() {
    UUID userA = UUID.randomUUID();
    HouseholdMember memberA =
        HouseholdTestData.member().withUserId(userA).withRole(HouseholdRole.primary).build();
    Household household = HouseholdTestData.household().withMember(memberA).build();

    Map<SlotKind, SlotDefault> slotDefaults = new LinkedHashMap<>();
    slotDefaults.put(SlotKind.dinner, new SlotDefault(false, 2, 60));
    HouseholdSettingsDocument doc =
        new HouseholdSettingsDocument(
            slotDefaults, List.of(), null, new HouseholdSchedulingPreferences());
    HouseholdSettings settings =
        HouseholdSettings.builder()
            .id(UUID.randomUUID())
            .householdId(household.getId())
            .document(doc)
            .build();

    SlotConfigurationDto result = resolver.resolve(household, settings);

    assertThat(result.slots()).hasSize(1);
    SlotConfigEntryDto dinner = result.slots().get(0);
    assertThat(dinner.shared()).isFalse();
    assertThat(dinner.eaterUserIdsIfPerPerson()).containsExactly(userA);
  }

  @Test
  void resolve_appendsCustomSlotsAfterBuiltIns() {
    UUID userA = UUID.randomUUID();
    HouseholdMember memberA =
        HouseholdTestData.member().withUserId(userA).withRole(HouseholdRole.primary).build();
    Household household = HouseholdTestData.household().withMember(memberA).build();

    CustomSlotDefinition custom =
        new CustomSlotDefinition("late-snack", "Late snack", SlotKind.snack, true, 1, 15);
    Map<SlotKind, SlotDefault> slotDefaults = new LinkedHashMap<>();
    slotDefaults.put(SlotKind.breakfast, new SlotDefault(true, 1, 30));
    HouseholdSettingsDocument doc =
        new HouseholdSettingsDocument(
            slotDefaults, List.of(custom), null, new HouseholdSchedulingPreferences());
    HouseholdSettings settings =
        HouseholdSettings.builder()
            .id(UUID.randomUUID())
            .householdId(household.getId())
            .document(doc)
            .build();

    SlotConfigurationDto result = resolver.resolve(household, settings);

    assertThat(result.slots()).hasSize(2);
    assertThat(result.slots().get(0).slotKey()).isEqualTo("breakfast");
    assertThat(result.slots().get(1).slotKey()).isEqualTo("late-snack");
    assertThat(result.slots().get(1).kind()).isEqualTo(SlotKind.snack);
  }

  /**
   * Custom slot with NULL headcount + NULL timeBudgetMin and {@code shared=false}. Exercises the
   * three ternary expressions on resolver lines 73-75 for the custom-slot branch (previously only
   * the slotDefault branch covered them): null headcount → DEFAULT_HEADCOUNT (1), null timeBudget →
   * DEFAULT_TIME_BUDGET_MIN (30), and {@code shared ? null : copyOf(eaters)} → the eater list. Each
   * assertion pins one ternary outcome so the substituted/negated mutants are killed.
   */
  @Test
  void resolve_customSlotWithNullDefaultsAndPerPerson_appliesFallbacksAndEaterList() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    HouseholdMember memberA =
        HouseholdTestData.member().withUserId(userA).withRole(HouseholdRole.primary).build();
    HouseholdMember memberB =
        HouseholdTestData.member().withUserId(userB).withRole(HouseholdRole.member).build();
    Household household =
        HouseholdTestData.household().withMember(memberA).withMember(memberB).build();

    CustomSlotDefinition custom =
        new CustomSlotDefinition("brunch", "Brunch", SlotKind.lunch, false, null, null);
    HouseholdSettingsDocument doc =
        new HouseholdSettingsDocument(
            new LinkedHashMap<>(), List.of(custom), null, new HouseholdSchedulingPreferences());
    HouseholdSettings settings =
        HouseholdSettings.builder()
            .id(UUID.randomUUID())
            .householdId(household.getId())
            .document(doc)
            .build();

    SlotConfigurationDto result = resolver.resolve(household, settings);

    assertThat(result.slots()).hasSize(1);
    SlotConfigEntryDto brunch = result.slots().get(0);
    assertThat(brunch.slotKey()).isEqualTo("brunch");
    assertThat(brunch.headcount()).isEqualTo(1); // null -> DEFAULT_HEADCOUNT
    assertThat(brunch.timeBudgetMin()).isEqualTo(30); // null -> DEFAULT_TIME_BUDGET_MIN
    assertThat(brunch.shared()).isFalse();
    assertThat(brunch.eaterUserIdsIfPerPerson()).containsExactlyInAnyOrder(userA, userB);
  }

  /**
   * Custom slot with {@code shared=true} but explicit headcount/timeBudget — pins the OTHER side of
   * the line-73/74 ternaries (non-null branch is kept) and the line-75 {@code shared ? null} true
   * branch, so a mutant that flips the condition is caught from both directions.
   */
  @Test
  void resolve_customSlotSharedWithExplicitValues_keepsValuesAndNullEaterList() {
    UUID userA = UUID.randomUUID();
    HouseholdMember memberA =
        HouseholdTestData.member().withUserId(userA).withRole(HouseholdRole.primary).build();
    Household household = HouseholdTestData.household().withMember(memberA).build();

    CustomSlotDefinition custom =
        new CustomSlotDefinition("supper", "Supper", SlotKind.dinner, true, 4, 90);
    HouseholdSettingsDocument doc =
        new HouseholdSettingsDocument(
            new LinkedHashMap<>(), List.of(custom), null, new HouseholdSchedulingPreferences());
    HouseholdSettings settings =
        HouseholdSettings.builder()
            .id(UUID.randomUUID())
            .householdId(household.getId())
            .document(doc)
            .build();

    SlotConfigEntryDto supper = resolver.resolve(household, settings).slots().get(0);

    assertThat(supper.headcount()).isEqualTo(4);
    assertThat(supper.timeBudgetMin()).isEqualTo(90);
    assertThat(supper.shared()).isTrue();
    assertThat(supper.eaterUserIdsIfPerPerson()).isNull();
  }
}
