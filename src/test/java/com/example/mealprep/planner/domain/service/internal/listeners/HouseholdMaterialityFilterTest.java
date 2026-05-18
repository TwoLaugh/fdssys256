package com.example.mealprep.planner.domain.service.internal.listeners;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.household.event.HouseholdSettingsChangedEvent;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link HouseholdMaterialityFilter} — planning-surface paths are material; cosmetic
 * paths (rename, timezone) are not.
 */
class HouseholdMaterialityFilterTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  private final HouseholdMaterialityFilter filter = new HouseholdMaterialityFilter();

  private Plan plan() {
    return PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.GENERATED, 1, 3);
  }

  private HouseholdSettingsChangedEvent event(Set<String> paths) {
    return new HouseholdSettingsChangedEvent(
        UUID.randomUUID(), UUID.randomUUID(), paths, UUID.randomUUID(), Instant.now());
  }

  @Test
  void material_whenSlotStructureChanged() {
    assertThat(filter.isMaterial(event(Set.of("mealStructure.dinnerEnabled")), plan())).isTrue();
  }

  @Test
  void material_whenMembershipChanged() {
    assertThat(filter.isMaterial(event(Set.of("members[2].userId")), plan())).isTrue();
  }

  @Test
  void material_whenBatchPolicyChanged_mixedWithCosmetic() {
    assertThat(filter.isMaterial(event(Set.of("displayName", "batchPolicy.maxSessions")), plan()))
        .isTrue();
  }

  @Test
  void immaterial_whenOnlyCosmeticPathsChanged() {
    assertThat(filter.isMaterial(event(Set.of("displayName", "timezone")), plan())).isFalse();
  }

  @Test
  void immaterial_whenNoPathsChanged() {
    assertThat(filter.isMaterial(event(Set.of()), plan())).isFalse();
  }

  @Test
  void caseInsensitiveSegmentMatch_doesNotFalseMatchSubstring() {
    // "memberships" is not a material prefix segment; "membersData" first segment is "membersdata"
    // which is also not in the set — neither should match (segment-aware, not substring).
    assertThat(filter.isMaterial(event(Set.of("membersData.note")), plan())).isFalse();
  }
}
