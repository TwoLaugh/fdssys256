package com.example.mealprep.preference.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.preference.api.dto.DietaryIdentityDto;
import com.example.mealprep.preference.api.dto.HardIntoleranceDto;
import com.example.mealprep.preference.api.dto.RemovedTier1Constraint;
import com.example.mealprep.preference.api.dto.Tier1Category;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import com.example.mealprep.preference.domain.entity.HardConstraints;
import com.example.mealprep.preference.domain.entity.HardIntolerance;
import com.example.mealprep.preference.testdata.HardConstraintsTestData;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Tier1RemovalDetector} — the pure GAP-04 removal-detection diff. Lives in
 * the {@code ...domain.service.internal} package so it can reach the package-private detector
 * directly, with no Spring context. Each gated category and each pass-through (add / reorder /
 * non-Tier-1) path is pinned so the mutation tests land on real branches.
 */
class Tier1RemovalDetectorTest {

  private static HardConstraints stored() {
    return HardConstraintsTestData.hardConstraints().withUserId(UUID.randomUUID()).build();
  }

  private static HardIntolerance intolerance(HardConstraints parent, String substance) {
    return HardIntolerance.builder()
        .id(UUID.randomUUID())
        .hardConstraints(parent)
        .substance(substance)
        .severity("coeliac")
        .notes(null)
        .build();
  }

  private static UpdateHardConstraintsRequest request(
      List<String> allergies,
      String base,
      List<String> medicalDiets,
      List<HardIntoleranceDto> intolerances) {
    return new UpdateHardConstraintsRequest(
        allergies,
        new DietaryIdentityDto(base, null, List.of()),
        medicalDiets,
        intolerances,
        List.of(),
        0L,
        null);
  }

  // ---------------- allergies ----------------

  @Test
  void removingAnAllergy_isDetected() {
    HardConstraints stored = stored();
    stored.setAllergies(new java.util.ArrayList<>(List.of("peanuts", "shellfish")));

    List<RemovedTier1Constraint> removed =
        Tier1RemovalDetector.detectRemovals(
            stored, request(List.of("peanuts"), "omnivore", List.of(), List.of()));

    assertThat(removed)
        .containsExactly(new RemovedTier1Constraint(Tier1Category.ALLERGY, "shellfish"));
  }

  @Test
  void addingAnAllergy_isNotDetected() {
    HardConstraints stored = stored();
    stored.setAllergies(new java.util.ArrayList<>(List.of("peanuts")));

    List<RemovedTier1Constraint> removed =
        Tier1RemovalDetector.detectRemovals(
            stored, request(List.of("peanuts", "shellfish"), "omnivore", List.of(), List.of()));

    assertThat(removed).isEmpty();
  }

  @Test
  void allergyCaseAndWhitespaceOnlyChange_isNotDetectedAsRemoval() {
    HardConstraints stored = stored();
    stored.setAllergies(new java.util.ArrayList<>(List.of("Peanuts")));

    List<RemovedTier1Constraint> removed =
        Tier1RemovalDetector.detectRemovals(
            stored, request(List.of("  peanuts "), "omnivore", List.of(), List.of()));

    assertThat(removed).isEmpty();
  }

  // ---------------- medical diets ----------------

  @Test
  void removingAMedicalDiet_isDetected() {
    HardConstraints stored = stored();
    stored.setMedicalDiets(new java.util.ArrayList<>(List.of("low_sodium", "renal")));

    List<RemovedTier1Constraint> removed =
        Tier1RemovalDetector.detectRemovals(
            stored, request(List.of(), "omnivore", List.of("low_sodium"), List.of()));

    assertThat(removed)
        .containsExactly(new RemovedTier1Constraint(Tier1Category.MEDICAL_DIET, "renal"));
  }

  // ---------------- severe intolerances ----------------

  @Test
  void removingASevereIntolerance_isDetectedBySubstance() {
    HardConstraints stored = stored();
    stored.getIntolerances().add(intolerance(stored, "gluten"));

    List<RemovedTier1Constraint> removed =
        Tier1RemovalDetector.detectRemovals(
            stored, request(List.of(), "omnivore", List.of(), List.of()));

    assertThat(removed)
        .containsExactly(new RemovedTier1Constraint(Tier1Category.SEVERE_INTOLERANCE, "gluten"));
  }

  @Test
  void editingAnIntoleranceSeverityOrNotes_keepingSubstance_isNotDetected() {
    HardConstraints stored = stored();
    stored.getIntolerances().add(intolerance(stored, "gluten"));

    List<RemovedTier1Constraint> removed =
        Tier1RemovalDetector.detectRemovals(
            stored,
            request(
                List.of(),
                "omnivore",
                List.of(),
                List.of(new HardIntoleranceDto("gluten", "mild", "now downgraded"))));

    assertThat(removed).isEmpty();
  }

  // ---------------- dietary identity base ----------------

  @Test
  void changingDietaryIdentityBase_isDetected() {
    HardConstraints stored = stored();
    stored.setDietaryIdentityBase("vegan");

    List<RemovedTier1Constraint> removed =
        Tier1RemovalDetector.detectRemovals(
            stored, request(List.of(), "omnivore", List.of(), List.of()));

    assertThat(removed)
        .containsExactly(new RemovedTier1Constraint(Tier1Category.DIETARY_IDENTITY_BASE, "vegan"));
  }

  @Test
  void keepingTheSameDietaryIdentityBase_isNotDetected() {
    HardConstraints stored = stored();
    stored.setDietaryIdentityBase("vegan");

    List<RemovedTier1Constraint> removed =
        Tier1RemovalDetector.detectRemovals(
            stored, request(List.of(), "vegan", List.of(), List.of()));

    assertThat(removed).isEmpty();
  }

  @Test
  void tighteningTheDietaryIdentityBase_isNotDetected() {
    // omnivore → vegetarian ADDS restriction (a tightening) — must not be gated.
    HardConstraints stored = stored();
    stored.setDietaryIdentityBase("omnivore");

    List<RemovedTier1Constraint> removed =
        Tier1RemovalDetector.detectRemovals(
            stored, request(List.of(), "vegetarian", List.of(), List.of()));

    assertThat(removed).isEmpty();
  }

  @Test
  void relaxingTheDietaryIdentityBaseToALessRestrictiveOne_isDetected() {
    // vegetarian → pescatarian re-allows fish — a relaxation of the safe default — gated.
    HardConstraints stored = stored();
    stored.setDietaryIdentityBase("vegetarian");

    List<RemovedTier1Constraint> removed =
        Tier1RemovalDetector.detectRemovals(
            stored, request(List.of(), "pescatarian", List.of(), List.of()));

    assertThat(removed)
        .containsExactly(
            new RemovedTier1Constraint(Tier1Category.DIETARY_IDENTITY_BASE, "vegetarian"));
  }

  @Test
  void lateralSwitchToAnIncomparableBase_isNotDetectedAsABaseRemoval() {
    // vegetarian → keto: incomparable exclusion sets — not a base removal (allergy/intolerance
    // gates still cover any genuine allergen exposure).
    HardConstraints stored = stored();
    stored.setDietaryIdentityBase("vegetarian");

    List<RemovedTier1Constraint> removed =
        Tier1RemovalDetector.detectRemovals(
            stored, request(List.of(), "keto", List.of(), List.of()));

    assertThat(removed).isEmpty();
  }

  // ---------------- combinations / pass-through ----------------

  @Test
  void multipleRemovalsAcrossCategories_areAllReported() {
    HardConstraints stored = stored();
    stored.setAllergies(new java.util.ArrayList<>(List.of("peanuts")));
    stored.setMedicalDiets(new java.util.ArrayList<>(List.of("low_sodium")));
    stored.setDietaryIdentityBase("vegetarian");
    stored.getIntolerances().add(intolerance(stored, "gluten"));

    List<RemovedTier1Constraint> removed =
        Tier1RemovalDetector.detectRemovals(
            stored, request(List.of(), "omnivore", List.of(), List.of()));

    assertThat(removed)
        .containsExactlyInAnyOrder(
            new RemovedTier1Constraint(Tier1Category.ALLERGY, "peanuts"),
            new RemovedTier1Constraint(Tier1Category.MEDICAL_DIET, "low_sodium"),
            new RemovedTier1Constraint(Tier1Category.SEVERE_INTOLERANCE, "gluten"),
            new RemovedTier1Constraint(Tier1Category.DIETARY_IDENTITY_BASE, "vegetarian"));
  }

  @Test
  void noChange_yieldsNoRemovals() {
    HardConstraints stored = stored();
    stored.setAllergies(new java.util.ArrayList<>(List.of("peanuts")));

    List<RemovedTier1Constraint> removed =
        Tier1RemovalDetector.detectRemovals(
            stored, request(List.of("peanuts"), "omnivore", List.of(), List.of()));

    assertThat(removed).isEmpty();
  }
}
