package com.example.mealprep.planner.domain.service.internal.listeners;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link PreferenceMaterialityFilter}. The merged preference module publishes a
 * dedicated hard-constraints event — any non-empty field change is, by construction, a
 * hard-constraint mutation and therefore ALWAYS material; only the degenerate empty change is
 * skipped.
 */
class PreferenceMaterialityFilterTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  private final PreferenceMaterialityFilter filter = new PreferenceMaterialityFilter();

  private Plan plan() {
    return PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.GENERATED, 1, 3);
  }

  @Test
  void material_whenAnAllergyFieldChanged() {
    HardConstraintsUpdatedEvent event =
        new HardConstraintsUpdatedEvent(
            UUID.randomUUID(), Set.of("allergies"), UUID.randomUUID(), Instant.now());
    assertThat(filter.isMaterial(event, plan())).isTrue();
  }

  @Test
  void material_whenADietaryIdentityFieldChanged() {
    HardConstraintsUpdatedEvent event =
        new HardConstraintsUpdatedEvent(
            UUID.randomUUID(), Set.of("dietaryIdentity"), UUID.randomUUID(), Instant.now());
    assertThat(filter.isMaterial(event, plan())).isTrue();
  }

  @Test
  void immaterial_whenNoFieldsChanged() {
    HardConstraintsUpdatedEvent event =
        new HardConstraintsUpdatedEvent(
            UUID.randomUUID(), Set.of(), UUID.randomUUID(), Instant.now());
    assertThat(filter.isMaterial(event, plan())).isFalse();
  }

  @Test
  void immaterial_whenChangedFieldsSetIsNull() {
    HardConstraintsUpdatedEvent event =
        new HardConstraintsUpdatedEvent(UUID.randomUUID(), null, UUID.randomUUID(), Instant.now());
    assertThat(filter.isMaterial(event, plan())).isFalse();
  }
}
