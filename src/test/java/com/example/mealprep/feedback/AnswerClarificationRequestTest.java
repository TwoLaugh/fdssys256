package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.feedback.api.dto.AnswerClarificationRequest;
import com.example.mealprep.feedback.spi.Destination;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Validates the {@code @AssertTrue isAtLeastOneProvided} cross-field rule. */
class AnswerClarificationRequestTest {

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

  @Test
  void bothNull_isInvalid() {
    var req = new AnswerClarificationRequest(null, null);
    assertThat(validator.validate(req)).isNotEmpty();
    assertThat(req.isAtLeastOneProvided()).isFalse();
  }

  @Test
  void blankTextAndNullDestination_isInvalid() {
    var req = new AnswerClarificationRequest(null, "   ");
    assertThat(validator.validate(req)).isNotEmpty();
    assertThat(req.isAtLeastOneProvided()).isFalse();
  }

  @Test
  void onlyDestination_isValid() {
    var req = new AnswerClarificationRequest(Destination.PREFERENCE, null);
    assertThat(validator.validate(req)).isEmpty();
    assertThat(req.isAtLeastOneProvided()).isTrue();
  }

  @Test
  void onlyText_isValid() {
    var req = new AnswerClarificationRequest(null, "I meant the standing preference");
    assertThat(validator.validate(req)).isEmpty();
    assertThat(req.isAtLeastOneProvided()).isTrue();
  }

  @Test
  void bothFields_isValid() {
    var req = new AnswerClarificationRequest(Destination.RECIPE, "lighter sauce");
    assertThat(validator.validate(req)).isEmpty();
    assertThat(req.isAtLeastOneProvided()).isTrue();
  }

  @Test
  void textOverMaxSize_isInvalid() {
    var req = new AnswerClarificationRequest(Destination.RECIPE, "a".repeat(4001));
    assertThat(validator.validate(req)).isNotEmpty();
  }
}
