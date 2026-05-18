package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.domain.entity.ActivityAdjustment;
import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import com.example.mealprep.nutrition.domain.entity.EatingWindow;
import com.example.mealprep.nutrition.domain.entity.IntakeAuditAction;
import com.example.mealprep.nutrition.domain.entity.IntakeAuditLog;
import com.example.mealprep.nutrition.domain.entity.IntakeDay;
import com.example.mealprep.nutrition.domain.entity.IntakeSlot;
import com.example.mealprep.nutrition.domain.entity.IntakeSnack;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.MicroTarget;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.PerMealDistributionEntry;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Behaviour-level tests for the nutrition aggregate roots — the hand-written association/replace
 * methods (NOT Lombok accessors). These kill the {@code VoidMethodCall} (dropped parent linkage),
 * {@code NullReturnVals} (collection-identity getter) and {@code NegateConditionals} (null-guard)
 * mutants those methods carry. Pure in-memory — no Spring context, no DB.
 */
class NutritionAggregateBehaviourTest {

  private PerMealDistributionEntry pmd(MealSlot slot) {
    return PerMealDistributionEntry.builder()
        .id(UUID.randomUUID())
        .mealSlot(slot)
        .calorieTarget(500)
        .proteinTargetG(new BigDecimal("30.0"))
        .build();
  }

  private MicroTarget micro(String key) {
    return MicroTarget.builder()
        .id(UUID.randomUUID())
        .nutrientKey(key)
        .targetValue(new BigDecimal("1.0"))
        .build();
  }

  private ActivityAdjustment activity(ActivityLevel level) {
    return ActivityAdjustment.builder()
        .id(UUID.randomUUID())
        .activityLevel(level)
        .calorieModifier(100)
        .carbModifierG(20)
        .build();
  }

  // ---------------- NutritionTargets.replacePerMealDistribution ----------------

  @Test
  void replacePerMealDistribution_links_each_child_to_parent_and_keeps_collection_identity() {
    NutritionTargets t = NutritionTestData.targets().build();
    List<PerMealDistributionEntry> originalList = t.getPerMealDistribution();
    PerMealDistributionEntry a = pmd(MealSlot.BREAKFAST);
    PerMealDistributionEntry b = pmd(MealSlot.LUNCH);

    t.replacePerMealDistribution(List.of(a, b));

    // Same List instance preserved (cleared + refilled, not reassigned) — kills the
    // "return new collection" / dropped-clear mutants.
    assertThat(t.getPerMealDistribution()).isSameAs(originalList);
    assertThat(t.getPerMealDistribution()).containsExactly(a, b);
    // Parent linkage set on every child — kills the VoidMethodCall on child.setTarget(this).
    assertThat(a.getTarget()).isSameAs(t);
    assertThat(b.getTarget()).isSameAs(t);
  }

  @Test
  void replacePerMealDistribution_clears_previous_entries() {
    NutritionTargets t = NutritionTestData.targets().build();
    t.replacePerMealDistribution(List.of(pmd(MealSlot.BREAKFAST)));
    t.replacePerMealDistribution(List.of(pmd(MealSlot.DINNER)));
    assertThat(t.getPerMealDistribution()).hasSize(1);
    assertThat(t.getPerMealDistribution().get(0).getMealSlot()).isEqualTo(MealSlot.DINNER);
  }

  @Test
  void replacePerMealDistribution_null_clears_without_adding() {
    NutritionTargets t = NutritionTestData.targets().build();
    t.replacePerMealDistribution(List.of(pmd(MealSlot.BREAKFAST)));
    t.replacePerMealDistribution(null);
    // Null path: cleared, loop skipped (kills the "skip clear" / "iterate anyway / NPE" mutants).
    assertThat(t.getPerMealDistribution()).isEmpty();
  }

  // ---------------- NutritionTargets.replaceMicroTargets ----------------

  @Test
  void replaceMicroTargets_links_children_and_preserves_identity() {
    NutritionTargets t = NutritionTestData.targets().build();
    List<MicroTarget> original = t.getMicroTargets();
    MicroTarget m = micro("vitamin_d");

    t.replaceMicroTargets(List.of(m));

    assertThat(t.getMicroTargets()).isSameAs(original).containsExactly(m);
    assertThat(m.getTarget()).isSameAs(t);
  }

  @Test
  void replaceMicroTargets_null_clears() {
    NutritionTargets t = NutritionTestData.targets().build();
    t.replaceMicroTargets(List.of(micro("iron")));
    t.replaceMicroTargets(null);
    assertThat(t.getMicroTargets()).isEmpty();
  }

  // ---------------- NutritionTargets.replaceActivityAdjustments ----------------

  @Test
  void replaceActivityAdjustments_links_children_and_preserves_identity() {
    NutritionTargets t = NutritionTestData.targets().build();
    List<ActivityAdjustment> original = t.getActivityAdjustments();
    ActivityAdjustment adj = activity(ActivityLevel.TRAINING_DAY);

    t.replaceActivityAdjustments(List.of(adj));

    assertThat(t.getActivityAdjustments()).isSameAs(original).containsExactly(adj);
    assertThat(adj.getTarget()).isSameAs(t);
  }

  @Test
  void replaceActivityAdjustments_null_clears() {
    NutritionTargets t = NutritionTestData.targets().build();
    t.replaceActivityAdjustments(List.of(activity(ActivityLevel.LIGHT_ACTIVITY)));
    t.replaceActivityAdjustments(null);
    assertThat(t.getActivityAdjustments()).isEmpty();
  }

  // ---------------- NutritionTargets.replaceEatingWindow ----------------

  @Test
  void replaceEatingWindow_sets_bidirectional_link() {
    NutritionTargets t = NutritionTestData.targets().build();
    EatingWindow w = EatingWindow.builder().id(UUID.randomUUID()).enabled(true).build();

    t.replaceEatingWindow(w);

    assertThat(t.getEatingWindow()).isSameAs(w);
    assertThat(w.getTarget()).isSameAs(t);
  }

  @Test
  void replaceEatingWindow_detaches_old_window_before_attaching_new() {
    NutritionTargets t = NutritionTestData.targets().build();
    EatingWindow first = EatingWindow.builder().id(UUID.randomUUID()).enabled(true).build();
    EatingWindow second = EatingWindow.builder().id(UUID.randomUUID()).enabled(false).build();

    t.replaceEatingWindow(first);
    t.replaceEatingWindow(second);

    // Old window's back-reference cleared (kills the VoidMethodCall on old.setTarget(null)).
    assertThat(first.getTarget()).isNull();
    assertThat(second.getTarget()).isSameAs(t);
    assertThat(t.getEatingWindow()).isSameAs(second);
  }

  @Test
  void replaceEatingWindow_null_removes_window_and_clears_old_back_reference() {
    NutritionTargets t = NutritionTestData.targets().build();
    EatingWindow w = EatingWindow.builder().id(UUID.randomUUID()).enabled(true).build();
    t.replaceEatingWindow(w);

    t.replaceEatingWindow(null);

    assertThat(t.getEatingWindow()).isNull();
    assertThat(w.getTarget()).isNull();
  }

  @Test
  void replaceEatingWindow_null_when_already_null_is_safe_noop() {
    NutritionTargets t = NutritionTestData.targets().build();
    // eatingWindow starts null — the (this.eatingWindow != null) guard must short-circuit
    // without NPE; kills the negated-conditional mutant on that guard.
    t.replaceEatingWindow(null);
    assertThat(t.getEatingWindow()).isNull();
  }

  // ---------------- IntakeDay.addSlot / addSnack ----------------

  @Test
  void addSlot_links_parent_and_appends() {
    IntakeDay day = IntakeDay.builder().id(UUID.randomUUID()).build();
    IntakeSlot slot =
        IntakeSlot.builder().id(UUID.randomUUID()).mealSlot(MealSlot.BREAKFAST).build();

    day.addSlot(slot);

    assertThat(slot.getIntakeDay()).isSameAs(day);
    assertThat(day.getSlots()).containsExactly(slot);
  }

  @Test
  void addSnack_links_parent_and_appends() {
    IntakeDay day = IntakeDay.builder().id(UUID.randomUUID()).build();
    IntakeSnack snack = IntakeSnack.builder().id(UUID.randomUUID()).calories(120).build();

    day.addSnack(snack);

    assertThat(snack.getIntakeDay()).isSameAs(day);
    assertThat(day.getSnacks()).containsExactly(snack);
  }

  @Test
  void addSlot_then_addSnack_keep_independent_collections() {
    IntakeDay day = IntakeDay.builder().id(UUID.randomUUID()).build();
    day.addSlot(IntakeSlot.builder().id(UUID.randomUUID()).mealSlot(MealSlot.LUNCH).build());
    day.addSnack(IntakeSnack.builder().id(UUID.randomUUID()).calories(50).build());
    assertThat(day.getSlots()).hasSize(1);
    assertThat(day.getSnacks()).hasSize(1);
  }

  // ---------------- IntakeAuditLog.getIntakeDayId null-guard ----------------

  @Test
  void getIntakeDayId_returns_id_when_intake_day_present() {
    UUID dayId = UUID.randomUUID();
    IntakeDay day = IntakeDay.builder().id(dayId).build();
    IntakeAuditLog log =
        new IntakeAuditLog(
            UUID.randomUUID(),
            day,
            UUID.randomUUID(),
            IntakeAuditAction.CONFIRM,
            MealSlot.BREAKFAST,
            null,
            null,
            null,
            Instant.parse("2026-05-18T08:00:00Z"));
    assertThat(log.getIntakeDayId()).isEqualTo(dayId);
    assertThat(log.getIntakeDay()).isSameAs(day);
  }

  @Test
  void getIntakeDayId_returns_null_when_intake_day_absent() {
    // The intakeDay == null ? null : intakeDay.getId() ternary — kills the negated-conditional
    // and the "always call getId() → NPE" mutant.
    IntakeAuditLog log =
        new IntakeAuditLog(
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            IntakeAuditAction.SNACK_ADD,
            null,
            UUID.randomUUID(),
            null,
            null,
            Instant.parse("2026-05-18T08:00:00Z"));
    assertThat(log.getIntakeDayId()).isNull();
  }
}
