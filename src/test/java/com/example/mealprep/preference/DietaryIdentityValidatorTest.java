package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.preference.api.dto.DietaryIdentityDto;
import com.example.mealprep.preference.api.dto.DietaryIdentityExceptionDto;
import com.example.mealprep.preference.api.dto.HardIntoleranceDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@code @ValidDietaryIdentity} (preference-4). Drives both validator implementations
 * through the public Jakarta Validation API: the type-level shape validator on {@link
 * DietaryIdentityDto} and the class-level collision validator on {@link
 * UpdateHardConstraintsRequest}.
 */
class DietaryIdentityValidatorTest {

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

  private static DietaryIdentityDto identity(String base, DietaryIdentityExceptionDto... ex) {
    return new DietaryIdentityDto(base, null, List.of(ex));
  }

  private static DietaryIdentityExceptionDto ex(String allows, String context) {
    return new DietaryIdentityExceptionDto(allows, "weekly", context);
  }

  private static UpdateHardConstraintsRequest request(
      DietaryIdentityDto identity, List<String> allergies, List<HardIntoleranceDto> intolerances) {
    return new UpdateHardConstraintsRequest(
        allergies, identity, List.of(), intolerances, List.of(), 0L, null);
  }

  // ---------------- shape: DietaryIdentityDto ----------------

  @Test
  void validIdentity_isAccepted() {
    Set<ConstraintViolation<DietaryIdentityDto>> v =
        validator.validate(identity("vegetarian", ex("fish", "weekend")));
    assertThat(v).isEmpty();
  }

  @Test
  void unknownBase_isRejected() {
    Set<ConstraintViolation<DietaryIdentityDto>> v =
        validator.validate(identity("carnivore-supreme", ex("fish", "any")));
    assertThat(v).isNotEmpty();
    assertThat(v).anyMatch(cv -> cv.getMessage().contains("unknown dietary base"));
  }

  @Test
  void unknownSubcategory_isRejected() {
    Set<ConstraintViolation<DietaryIdentityDto>> v =
        validator.validate(identity("vegetarian", ex("dinosaur", "any")));
    assertThat(v).anyMatch(cv -> cv.getMessage().contains("unknown exception sub-category"));
  }

  @Test
  void conditionalFreeOfQualifier_isAcceptedAsSubcategory() {
    Set<ConstraintViolation<DietaryIdentityDto>> v =
        validator.validate(identity("vegan", ex("lactose_free", "any")));
    assertThat(v).isEmpty();
  }

  @Test
  void unknownContext_isRejected() {
    Set<ConstraintViolation<DietaryIdentityDto>> v =
        validator.validate(identity("vegetarian", ex("fish", "fortnightly")));
    assertThat(v).anyMatch(cv -> cv.getMessage().contains("unknown exception context"));
  }

  // ---------------- collision: UpdateHardConstraintsRequest ----------------

  @Test
  void plainExceptionCollidingWithAllergy_isRejected() {
    UpdateHardConstraintsRequest req =
        request(identity("vegetarian", ex("fish", "any")), List.of("fish"), List.of());
    Set<ConstraintViolation<UpdateHardConstraintsRequest>> v = validator.validate(req);
    assertThat(v).anyMatch(cv -> cv.getMessage().contains("collides with a declared allergy"));
  }

  @Test
  void plainExceptionCollidingWithIntolerance_isRejected() {
    UpdateHardConstraintsRequest req =
        request(
            identity("vegan", ex("dairy", "any")),
            List.of(),
            List.of(new HardIntoleranceDto("dairy", "severe", null)));
    Set<ConstraintViolation<UpdateHardConstraintsRequest>> v = validator.validate(req);
    assertThat(v).anyMatch(cv -> cv.getMessage().contains("collides with a declared allergy"));
  }

  @Test
  void conditionalFreeOfExceptionWithAllergy_isAllowed() {
    // lactose_free only widens to the explicitly-safe variant, so it does NOT collide with a dairy
    // allergy (the filter still flags any untagged form as AMBIGUOUS).
    UpdateHardConstraintsRequest req =
        request(identity("vegan", ex("lactose_free", "any")), List.of("dairy"), List.of());
    Set<ConstraintViolation<UpdateHardConstraintsRequest>> v = validator.validate(req);
    assertThat(v).isEmpty();
  }

  @Test
  void nonCollidingException_isAccepted() {
    UpdateHardConstraintsRequest req =
        request(identity("vegetarian", ex("fish", "weekend")), List.of("peanut"), List.of());
    Set<ConstraintViolation<UpdateHardConstraintsRequest>> v = validator.validate(req);
    assertThat(v).isEmpty();
  }

  @Test
  void requestWithUnknownBase_surfacesShapeViolationViaCascade() {
    // The DTO shape validator fires through the request's @Valid cascade, so a bad base on the
    // request path is still caught.
    UpdateHardConstraintsRequest req =
        request(identity("not-a-base", ex("fish", "any")), List.of(), List.of());
    Set<ConstraintViolation<UpdateHardConstraintsRequest>> v = validator.validate(req);
    assertThat(v).anyMatch(cv -> cv.getMessage().contains("unknown dietary base"));
  }
}
