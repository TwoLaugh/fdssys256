package com.example.mealprep.preference.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link HardConstraints} aggregate-root behaviour — specifically the three
 * {@code replace*} in-place collection mutators and the accessor round-trip.
 *
 * <p>At baseline the {@code replace*} bodies had survivors for "removed call to {@code
 * List::clear}" and "removed call to {@code child.setHardConstraints(this)}". These tests pin both:
 * after a replace the collection identity is preserved (same {@code List} reference — required so
 * Hibernate tracks orphan removal) AND contains exactly the replacements, AND each child's
 * back-reference points at the parent. The null-replacement path (clear-only, no NPE) is also
 * covered, killing the {@code if (replacements != null)} negation.
 */
class HardConstraintsEntityTest {

  private static HardConstraints newParent() {
    return HardConstraints.builder()
        .id(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .allergies(new ArrayList<>(List.of("peanut")))
        .dietaryIdentityBase("omnivore")
        .medicalDiets(new ArrayList<>())
        .build();
  }

  @Test
  void replaceExceptions_preservesCollectionIdentity_reparentsChildren_andReplacesContents() {
    HardConstraints parent = newParent();
    List<DietaryIdentityException> original = parent.getExceptions();
    DietaryIdentityException seed =
        DietaryIdentityException.builder()
            .id(UUID.randomUUID())
            .allows("fish")
            .context("HOME")
            .build();
    parent.replaceExceptions(new ArrayList<>(List.of(seed)));

    DietaryIdentityException repl =
        DietaryIdentityException.builder()
            .id(UUID.randomUUID())
            .allows("dairy")
            .context("DINING_OUT")
            .build();
    parent.replaceExceptions(new ArrayList<>(List.of(repl)));

    // clear() + add() in place → same List instance (Hibernate orphan-removal contract).
    assertThat(parent.getExceptions()).isSameAs(original);
    assertThat(parent.getExceptions()).containsExactly(repl);
    assertThat(repl.getHardConstraints()).isSameAs(parent);
  }

  @Test
  void replaceExceptions_null_clearsWithoutNpe() {
    HardConstraints parent = newParent();
    parent.replaceExceptions(
        new ArrayList<>(
            List.of(
                DietaryIdentityException.builder()
                    .id(UUID.randomUUID())
                    .allows("fish")
                    .context("HOME")
                    .build())));
    parent.replaceExceptions(null);
    assertThat(parent.getExceptions()).isEmpty();
  }

  @Test
  void replaceIntolerances_preservesIdentity_reparents_andReplaces() {
    HardConstraints parent = newParent();
    List<HardIntolerance> original = parent.getIntolerances();
    HardIntolerance repl =
        HardIntolerance.builder()
            .id(UUID.randomUUID())
            .substance("lactose")
            .severity("HIGH")
            .build();
    parent.replaceIntolerances(new ArrayList<>(List.of(repl)));

    assertThat(parent.getIntolerances()).isSameAs(original);
    assertThat(parent.getIntolerances()).containsExactly(repl);
    assertThat(repl.getHardConstraints()).isSameAs(parent);
  }

  @Test
  void replaceIntolerances_null_clearsWithoutNpe() {
    HardConstraints parent = newParent();
    parent.replaceIntolerances(
        new ArrayList<>(
            List.of(
                HardIntolerance.builder()
                    .id(UUID.randomUUID())
                    .substance("gluten")
                    .severity("LOW")
                    .build())));
    parent.replaceIntolerances(null);
    assertThat(parent.getIntolerances()).isEmpty();
  }

  @Test
  void replaceAgeRestrictions_preservesIdentity_reparents_andReplaces() {
    HardConstraints parent = newParent();
    List<AgeRestriction> original = parent.getAgeRestrictions();
    AgeRestriction repl =
        AgeRestriction.builder()
            .id(UUID.randomUUID())
            .ruleKey("no_whole_nuts")
            .autoPopulated(true)
            .build();
    parent.replaceAgeRestrictions(new ArrayList<>(List.of(repl)));

    assertThat(parent.getAgeRestrictions()).isSameAs(original);
    assertThat(parent.getAgeRestrictions()).containsExactly(repl);
    assertThat(repl.getHardConstraints()).isSameAs(parent);
  }

  @Test
  void replaceAgeRestrictions_null_clearsWithoutNpe() {
    HardConstraints parent = newParent();
    parent.replaceAgeRestrictions(
        new ArrayList<>(
            List.of(
                AgeRestriction.builder()
                    .id(UUID.randomUUID())
                    .ruleKey("no_honey")
                    .autoPopulated(false)
                    .build())));
    parent.replaceAgeRestrictions(null);
    assertThat(parent.getAgeRestrictions()).isEmpty();
  }

  @Test
  void replace_emptyList_clearsAllExisting() {
    HardConstraints parent = newParent();
    parent.replaceAgeRestrictions(
        new ArrayList<>(
            List.of(
                AgeRestriction.builder()
                    .id(UUID.randomUUID())
                    .ruleKey("k")
                    .autoPopulated(true)
                    .build())));
    parent.replaceAgeRestrictions(new ArrayList<>());
    assertThat(parent.getAgeRestrictions()).isEmpty();
  }

  @Test
  void accessors_roundTrip() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Instant created = Instant.parse("2026-03-04T05:06:07Z");
    Instant updated = Instant.parse("2026-04-05T06:07:08Z");
    HardConstraints hc =
        HardConstraints.builder()
            .id(id)
            .userId(userId)
            .allergies(List.of("peanut", "shellfish"))
            .dietaryIdentityBase("vegetarian")
            .dietaryIdentityLabel("Lacto-vegetarian")
            .medicalDiets(List.of("low_fodmap"))
            .version(7L)
            .createdAt(created)
            .updatedAt(updated)
            .build();

    assertThat(hc.getId()).isEqualTo(id);
    assertThat(hc.getUserId()).isEqualTo(userId);
    assertThat(hc.getAllergies()).containsExactly("peanut", "shellfish");
    assertThat(hc.getDietaryIdentityBase()).isEqualTo("vegetarian");
    assertThat(hc.getDietaryIdentityLabel()).isEqualTo("Lacto-vegetarian");
    assertThat(hc.getMedicalDiets()).containsExactly("low_fodmap");
    assertThat(hc.getVersion()).isEqualTo(7L);
    assertThat(hc.getCreatedAt()).isEqualTo(created);
    assertThat(hc.getUpdatedAt()).isEqualTo(updated);
    // @Builder.Default collections are initialised, not null.
    assertThat(hc.getExceptions()).isEmpty();
    assertThat(hc.getIntolerances()).isEmpty();
    assertThat(hc.getAgeRestrictions()).isEmpty();
  }
}
