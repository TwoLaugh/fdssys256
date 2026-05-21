package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.preference.api.dto.UpdateLifestyleConfigRequest;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.NoveltyMode;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.NoveltyTolerance;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@code NoveltyToleranceValidator}. Drives the validator through the public Jakarta
 * Validation API so the annotation wiring is exercised end-to-end (annotation -> validator class ->
 * violations).
 */
class NoveltyToleranceValidatorTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private static UpdateLifestyleConfigRequest requestWithNovelty(NoveltyTolerance n) {
    LifestyleConfigDocument doc =
        new LifestyleConfigDocument(
            null, null, n, null, null, null, null, null, null, null, null, null);
    return new UpdateLifestyleConfigRequest(doc, 0L);
  }

  @Test
  void nullNoveltySection_isAccepted() {
    Set<ConstraintViolation<UpdateLifestyleConfigRequest>> violations =
        validator.validate(requestWithNovelty(null));
    assertThat(violations).isEmpty();
  }

  @Test
  void rotationMode_withPositiveRotationSize_isAccepted() {
    NoveltyTolerance n =
        new NoveltyTolerance(
            Map.of("dinner", new NoveltyMode("rotation", 6, null, null, null)), Map.of(), Map.of());
    assertThat(validator.validate(requestWithNovelty(n))).isEmpty();
  }

  @Test
  void rotationMode_withZeroRotationSize_isRejected() {
    NoveltyTolerance n =
        new NoveltyTolerance(
            Map.of("dinner", new NoveltyMode("rotation", 0, null, null, null)), Map.of(), Map.of());
    Set<ConstraintViolation<UpdateLifestyleConfigRequest>> v =
        validator.validate(requestWithNovelty(n));
    assertThat(v).isNotEmpty();
    assertThat(v).anyMatch(cv -> cv.getMessage().contains("rotationSize"));
  }

  @Test
  void batchRepeatMode_withNullMaxConsecutiveSame_isRejected() {
    NoveltyTolerance n =
        new NoveltyTolerance(
            Map.of("dinner", new NoveltyMode("batch_repeat", null, null, null, null)),
            Map.of(),
            Map.of());
    Set<ConstraintViolation<UpdateLifestyleConfigRequest>> v =
        validator.validate(requestWithNovelty(n));
    assertThat(v).anyMatch(cv -> cv.getMessage().contains("maxConsecutiveSame"));
  }

  @Test
  void highVarietyMode_withZeroNewPerWeek_isRejected() {
    NoveltyTolerance n =
        new NoveltyTolerance(
            Map.of("dinner", new NoveltyMode("high_variety", null, null, null, 0)),
            Map.of(),
            Map.of());
    Set<ConstraintViolation<UpdateLifestyleConfigRequest>> v =
        validator.validate(requestWithNovelty(n));
    assertThat(v).anyMatch(cv -> cv.getMessage().contains("newPerWeek"));
  }

  @Test
  void staticMode_withNoModeSpecificFields_isAccepted() {
    NoveltyTolerance n =
        new NoveltyTolerance(
            Map.of("dinner", new NoveltyMode("static", null, null, null, null)),
            Map.of(),
            Map.of());
    assertThat(validator.validate(requestWithNovelty(n))).isEmpty();
  }

  @Test
  void staticMode_withRotationSizeSet_isRejected() {
    NoveltyTolerance n =
        new NoveltyTolerance(
            Map.of("dinner", new NoveltyMode("static", 4, null, null, null)), Map.of(), Map.of());
    Set<ConstraintViolation<UpdateLifestyleConfigRequest>> v =
        validator.validate(requestWithNovelty(n));
    assertThat(v).anyMatch(cv -> cv.getMessage().contains("static"));
  }

  @Test
  void unknownMode_isRejected() {
    NoveltyTolerance n =
        new NoveltyTolerance(
            Map.of("dinner", new NoveltyMode("randomly_chaotic", null, null, null, null)),
            Map.of(),
            Map.of());
    Set<ConstraintViolation<UpdateLifestyleConfigRequest>> v =
        validator.validate(requestWithNovelty(n));
    assertThat(v).anyMatch(cv -> cv.getMessage().contains("unknown mode"));
  }

  @Test
  void negativeRecipeRepeatCooldown_isRejected() {
    NoveltyTolerance n =
        new NoveltyTolerance(
            Map.of("dinner", new NoveltyMode("static", null, null, null, null)),
            Map.of("default", -1),
            Map.of());
    Set<ConstraintViolation<UpdateLifestyleConfigRequest>> v =
        validator.validate(requestWithNovelty(n));
    assertThat(v).anyMatch(cv -> cv.getMessage().contains("recipeRepeatCooldownWeeks"));
  }

  @Test
  void blankIngredientFrequencyCapKey_isRejected() {
    Map<String, String> caps = new HashMap<>();
    caps.put(" ", "weekly");
    NoveltyTolerance n =
        new NoveltyTolerance(
            Map.of("dinner", new NoveltyMode("static", null, null, null, null)), Map.of(), caps);
    Set<ConstraintViolation<UpdateLifestyleConfigRequest>> v =
        validator.validate(requestWithNovelty(n));
    assertThat(v).anyMatch(cv -> cv.getMessage().contains("ingredientFrequencyCaps"));
  }
}
