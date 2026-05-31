package com.example.mealprep.preference.validation;

import com.example.mealprep.preference.api.dto.DietaryIdentityDto;
import com.example.mealprep.preference.api.dto.DietaryIdentityExceptionDto;
import com.example.mealprep.preference.api.dto.HardIntoleranceDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link ValidDietaryIdentity} implementation targeting the whole {@link
 * UpdateHardConstraintsRequest}. Performs the safety collision check: no dietary-identity exception
 * may {@code allows} a substance the user has simultaneously listed as an allergy or hard
 * intolerance.
 *
 * <p>Shape validation (base / sub-category / context) is NOT repeated here — it is owned by the
 * type-level {@link DietaryIdentityValidator} on {@link DietaryIdentityDto}, which fires via the
 * request's {@code @Valid DietaryIdentityDto dietaryIdentity} cascade. Splitting the two avoids
 * duplicate shape violations on the request path.
 *
 * <p>The collision check compares the exception's {@code allows} value (and, for a conditional
 * "X-free" qualifier, its stripped base substance — e.g. {@code lactose_free} → {@code lactose})
 * against the request's {@code allergies} and {@code intolerances[].substance}. A plain
 * (non-free-of) exception that directly re-admits a declared allergen is the dangerous case the LLD
 * guards against; a conditional "X-free" exception is allowed even when the base substance is an
 * allergy, because it only widens to the explicitly-safe variant (the filter still flags any
 * untagged form as AMBIGUOUS).
 */
public class DietaryIdentityRequestValidator
    implements ConstraintValidator<ValidDietaryIdentity, UpdateHardConstraintsRequest> {

  @Override
  public boolean isValid(UpdateHardConstraintsRequest req, ConstraintValidatorContext ctx) {
    if (req == null) {
      return true;
    }
    DietaryIdentityDto identity = req.dietaryIdentity();
    if (identity == null) {
      // @NotNull on dietaryIdentity handles this; nothing to cross-check.
      return true;
    }
    // Shape validation is owned by the type-level DietaryIdentityValidator (fires via @Valid).
    return validateNoAllergyCollision(req, identity, ctx);
  }

  private boolean validateNoAllergyCollision(
      UpdateHardConstraintsRequest req,
      DietaryIdentityDto identity,
      ConstraintValidatorContext ctx) {
    List<DietaryIdentityExceptionDto> exceptions = identity.exceptions();
    if (exceptions == null || exceptions.isEmpty()) {
      return true;
    }
    Set<String> banned = new HashSet<>();
    if (req.allergies() != null) {
      for (String a : req.allergies()) {
        String n = DietaryIdentityRules.norm(a);
        if (n != null) {
          banned.add(n);
        }
      }
    }
    if (req.intolerances() != null) {
      for (HardIntoleranceDto hi : req.intolerances()) {
        if (hi == null) {
          continue;
        }
        String n = DietaryIdentityRules.norm(hi.substance());
        if (n != null) {
          banned.add(n);
        }
      }
    }
    if (banned.isEmpty()) {
      return true;
    }
    boolean ok = true;
    for (int i = 0; i < exceptions.size(); i++) {
      DietaryIdentityExceptionDto ex = exceptions.get(i);
      if (ex == null) {
        continue;
      }
      String allows = DietaryIdentityRules.norm(ex.allows());
      if (allows == null) {
        continue;
      }
      // A conditional "X-free" exception only ever widens to the explicitly-safe variant, so it is
      // NOT a collision even when its base substance is an allergy — the filter still flags any
      // untagged form. A plain exception directly re-admitting a banned substance IS a collision.
      if (DietaryIdentityRules.isFreeOfQualifier(allows)) {
        continue;
      }
      if (banned.contains(allows)) {
        ok =
            DietaryIdentityValidationSupport.violation(
                    ctx,
                    "exception allows '"
                        + ex.allows()
                        + "' which collides with a declared allergy or hard intolerance",
                    "dietaryIdentity.exceptions[" + i + "].allows")
                && ok;
      }
    }
    return ok;
  }
}
