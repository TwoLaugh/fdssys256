package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.preference.api.dto.FilterContext;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage of the server-side exclusion union (ticket {@code
 * discovery-server-side-exclusions}): the SERVER derives the caller's hard-constraint snapshot via
 * the preference seam and unions it with client-supplied keys — client keys are additive only and
 * can never weaken the snapshot.
 */
@ExtendWith(MockitoExtension.class)
class HardConstraintSnapshotAssemblerTest {

  private static final UUID USER_ID = UUID.randomUUID();

  @Mock private HardConstraintFilterService hardConstraintFilter;

  private HardConstraintSnapshotAssembler assembler() {
    return new HardConstraintSnapshotAssembler(hardConstraintFilter);
  }

  private static DiscoveryConstraints constraintsWithClientKeys(List<String> clientKeys) {
    return new DiscoveryConstraints(
        1,
        List.of("East Asian"),
        List.of("dinner"),
        45,
        clientKeys,
        List.of("vegetarian"),
        List.of("lighter dishes"),
        3);
  }

  private void serverSnapshotIs(Set<String> keys) {
    when(hardConstraintFilter.exclusionKeySnapshot(eq(USER_ID), eq(FilterContext.ANY)))
        .thenReturn(keys);
  }

  // ---- the attack case at unit level: client omits the user's allergens ----

  @Test
  void clientSendsEmptyList_serverSnapshotStillApplied() {
    serverSnapshotIs(Set.of("peanut", "peanut_oil", "satay_sauce"));

    DiscoveryConstraints effective =
        assembler().withServerExclusions(USER_ID, constraintsWithClientKeys(List.of()));

    assertThat(effective.mustExcludeIngredientMappingKeys())
        .containsExactly("peanut", "peanut_oil", "satay_sauce"); // sorted union
  }

  @Test
  void clientSendsNullList_serverSnapshotStillApplied() {
    serverSnapshotIs(Set.of("peanut"));

    DiscoveryConstraints effective =
        assembler().withServerExclusions(USER_ID, constraintsWithClientKeys(null));

    assertThat(effective.mustExcludeIngredientMappingKeys()).containsExactly("peanut");
  }

  // ---- union: client keys are kept, server keys added, no duplicates ----

  @Test
  void clientExtraKeys_unionedWithServerSnapshot_deduplicated() {
    serverSnapshotIs(Set.of("peanut", "peanut_oil"));

    DiscoveryConstraints effective =
        assembler()
            .withServerExclusions(
                USER_ID, constraintsWithClientKeys(List.of("mushroom", "peanut")));

    assertThat(effective.mustExcludeIngredientMappingKeys())
        .containsExactly("mushroom", "peanut", "peanut_oil");
  }

  // ---- no-constraints user: client keys pass through unchanged ----

  @Test
  void noServerConstraints_clientKeysPassThrough() {
    serverSnapshotIs(Set.of());

    DiscoveryConstraints effective =
        assembler().withServerExclusions(USER_ID, constraintsWithClientKeys(List.of("mushroom")));

    assertThat(effective.mustExcludeIngredientMappingKeys()).containsExactly("mushroom");
  }

  @Test
  void noServerConstraints_andNoClientKeys_preservesNullVsEmptyShape() {
    serverSnapshotIs(Set.of());

    DiscoveryConstraints nullShape =
        assembler().withServerExclusions(USER_ID, constraintsWithClientKeys(null));
    DiscoveryConstraints emptyShape =
        assembler().withServerExclusions(USER_ID, constraintsWithClientKeys(List.of()));

    assertThat(nullShape.mustExcludeIngredientMappingKeys()).isNull();
    assertThat(emptyShape.mustExcludeIngredientMappingKeys()).isEmpty();
  }

  // ---- normalisation before union (core-03) ----

  @Test
  void keysNormalisedBeforeUnion_mixedCaseClientKeyMergesWithServerKey() {
    serverSnapshotIs(Set.of("chicken breast"));

    DiscoveryConstraints effective =
        assembler()
            .withServerExclusions(
                USER_ID, constraintsWithClientKeys(List.of("  Chicken   Breast ", "Mushroom")));

    assertThat(effective.mustExcludeIngredientMappingKeys())
        .containsExactly("chicken breast", "mushroom");
  }

  @Test
  void blankAndNullClientKeys_dropped() {
    serverSnapshotIs(Set.of("peanut"));

    java.util.List<String> withNulls = new java.util.ArrayList<>();
    withNulls.add("   ");
    withNulls.add(null);
    withNulls.add("mushroom");

    DiscoveryConstraints effective =
        assembler().withServerExclusions(USER_ID, constraintsWithClientKeys(withNulls));

    assertThat(effective.mustExcludeIngredientMappingKeys()).containsExactly("mushroom", "peanut");
  }

  // ---- every other constraints field passes through untouched ----

  @Test
  void nonExclusionFields_passThroughUnchanged() {
    serverSnapshotIs(Set.of("peanut"));
    DiscoveryConstraints client = constraintsWithClientKeys(List.of("mushroom"));

    DiscoveryConstraints effective = assembler().withServerExclusions(USER_ID, client);

    assertThat(effective.schemaVersion()).isEqualTo(client.schemaVersion());
    assertThat(effective.requiredCuisines()).isEqualTo(client.requiredCuisines());
    assertThat(effective.requiredMealTypes()).isEqualTo(client.requiredMealTypes());
    assertThat(effective.maxTotalTimeMins()).isEqualTo(client.maxTotalTimeMins());
    assertThat(effective.dietaryFlags()).isEqualTo(client.dietaryFlags());
    assertThat(effective.preferenceHints()).isEqualTo(client.preferenceHints());
    assertThat(effective.maxRecipesPerSource()).isEqualTo(client.maxRecipesPerSource());
  }

  @Test
  void nullConstraints_returnsNull_withoutCallingPreference() {
    assertThat(assembler().withServerExclusions(USER_ID, null)).isNull();
    org.mockito.Mockito.verifyNoInteractions(hardConstraintFilter);
  }
}
