package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.service.internal.DataQualityGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pins the data-quality floor expansion the library list/search relies on: {@code USER_VERIFIED >
 * IMPORTED ≈ AI_GENERATED > WEB_DISCOVERED}, with the IMPORTED/AI_GENERATED tie meaning a floor at
 * either admits both. Exact-set assertions so a mutated rank table cannot survive.
 */
class DataQualityGateTest {

  @Test
  void nullFloor_admitsEveryTier() {
    assertThat(DataQualityGate.atOrAbove(null)).containsExactlyInAnyOrder(DataQuality.values());
  }

  @Test
  void userVerifiedFloor_admitsOnlyUserVerified() {
    assertThat(DataQualityGate.atOrAbove(DataQuality.USER_VERIFIED))
        .containsExactly(DataQuality.USER_VERIFIED);
  }

  @Test
  void importedFloor_admitsTheTieAndAbove_excludesWebDiscovered() {
    assertThat(DataQualityGate.atOrAbove(DataQuality.IMPORTED))
        .containsExactlyInAnyOrder(
            DataQuality.USER_VERIFIED, DataQuality.IMPORTED, DataQuality.AI_GENERATED);
  }

  @Test
  void aiGeneratedFloor_isTheSameSetAsImported_theTieIsSymmetric() {
    assertThat(DataQualityGate.atOrAbove(DataQuality.AI_GENERATED))
        .containsExactlyInAnyOrderElementsOf(DataQualityGate.atOrAbove(DataQuality.IMPORTED));
  }

  @Test
  void webDiscoveredFloor_admitsEveryTier() {
    assertThat(DataQualityGate.atOrAbove(DataQuality.WEB_DISCOVERED))
        .containsExactlyInAnyOrder(DataQuality.values());
  }

  @ParameterizedTest
  @EnumSource(DataQuality.class)
  void everyFloor_includesItself_andIsNeverEmpty(DataQuality floor) {
    assertThat(DataQualityGate.atOrAbove(floor)).isNotEmpty().contains(floor);
  }
}
