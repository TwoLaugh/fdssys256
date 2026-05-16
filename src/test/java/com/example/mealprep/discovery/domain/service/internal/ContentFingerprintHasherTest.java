package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.api.dto.ParsedRecipe.ParsedIngredient;
import com.example.mealprep.discovery.api.dto.ParsedRecipe.ParsedMethodStep;
import com.example.mealprep.discovery.api.dto.ParsedRecipe.ParsedRecipeMetadata;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentFingerprintHasherTest {

  private final ContentFingerprintHasher hasher = new ContentFingerprintHasher();

  @Test
  void identicalRecipesProduceIdenticalFingerprint() {
    ParsedRecipe a = recipe(List.of("garlic", "onion"), List.of("chop", "fry"));
    ParsedRecipe b = recipe(List.of("garlic", "onion"), List.of("chop", "fry"));

    assertThat(hasher.fingerprint(a)).isEqualTo(hasher.fingerprint(b));
  }

  @Test
  void ingredientOrderDoesNotAffectFingerprint() {
    ParsedRecipe a = recipe(List.of("garlic", "onion"), List.of("chop", "fry"));
    ParsedRecipe b = recipe(List.of("onion", "garlic"), List.of("chop", "fry"));

    assertThat(hasher.fingerprint(a)).isEqualTo(hasher.fingerprint(b));
  }

  @Test
  void caseAndWhitespaceVariationsCollapseToSameFingerprint() {
    ParsedRecipe a = recipe(List.of("Garlic", "ONION"), List.of("Chop  the  onion", "fry"));
    ParsedRecipe b = recipe(List.of("garlic", "onion"), List.of("chop the onion", "fry"));

    assertThat(hasher.fingerprint(a)).isEqualTo(hasher.fingerprint(b));
  }

  @Test
  void differentMethodTextYieldsDifferentFingerprint() {
    ParsedRecipe a = recipe(List.of("garlic"), List.of("chop"));
    ParsedRecipe b = recipe(List.of("garlic"), List.of("dice"));

    assertThat(hasher.fingerprint(a)).isNotEqualTo(hasher.fingerprint(b));
  }

  @Test
  void fingerprintIs64HexChars() {
    String fp = hasher.fingerprint(recipe(List.of("garlic"), List.of("chop")));

    assertThat(fp).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  void nullIngredientKeysAreFilteredOut() {
    ParsedRecipe withNulls = recipe(Arrays.asList(null, "garlic", null), List.of("chop"));
    ParsedRecipe withoutNulls = recipe(List.of("garlic"), List.of("chop"));

    assertThat(hasher.fingerprint(withNulls)).isEqualTo(hasher.fingerprint(withoutNulls));
  }

  private static ParsedRecipe recipe(List<String> ingredientKeys, List<String> methodSteps) {
    List<ParsedIngredient> ingredients =
        ingredientKeys.stream()
            .map(
                key ->
                    new ParsedIngredient(
                        key == null ? "?" : key, key, BigDecimal.ONE, "cup", null, false))
            .toList();
    List<ParsedMethodStep> method = new java.util.ArrayList<>();
    for (int i = 0; i < methodSteps.size(); i++) {
      method.add(new ParsedMethodStep(i + 1, methodSteps.get(i), null));
    }
    ParsedRecipeMetadata md =
        new ParsedRecipeMetadata(4, 10, 20, 30, List.of(), "Italian", List.of("dinner"));
    return new ParsedRecipe(
        "https://example.test/r",
        "name",
        "desc",
        ingredients,
        method,
        md,
        "JSON_LD",
        new BigDecimal("0.90"));
  }
}
