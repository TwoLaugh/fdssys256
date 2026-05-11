package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.domain.entity.MethodOverlayLine;
import com.example.mealprep.recipe.domain.entity.RecipeSubstitution;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pure-logic component (no DB access). Overlays one or more substitutions onto a base recipe body —
 * per LLD line 749 ("the base version is not mutated; the substitution stays an overlay").
 *
 * <p>Substitutions are applied in {@code created_at ASC} order so that overlapping swaps land
 * deterministically (earliest first). Returns a new {@link NewVersionInput}; the input is not
 * mutated.
 */
@Component
public class SubstitutionOverlayApplier {

  private static final Logger log = LoggerFactory.getLogger(SubstitutionOverlayApplier.class);

  /**
   * Apply the supplied substitutions to {@code baseBody} and return the overlaid body. Method
   * overlays at non-existent step numbers are logged at WARN and skipped.
   */
  public NewVersionInput apply(NewVersionInput baseBody, List<RecipeSubstitution> subs) {
    List<CreateIngredientRequest> ings = new ArrayList<>(baseBody.ingredients());
    List<CreateMethodStepRequest> steps = new ArrayList<>(baseBody.method());
    if (subs == null || subs.isEmpty()) {
      return new NewVersionInput(ings, steps, baseBody.metadata(), baseBody.tags());
    }
    List<RecipeSubstitution> sorted = new ArrayList<>(subs);
    sorted.sort(Comparator.comparing(RecipeSubstitution::getCreatedAt));
    for (RecipeSubstitution s : sorted) {
      for (int i = 0; i < ings.size(); i++) {
        CreateIngredientRequest cur = ings.get(i);
        if (s.getOriginalMappingKey().equals(cur.ingredientMappingKey())) {
          ings.set(
              i,
              new CreateIngredientRequest(
                  cur.lineOrder(),
                  s.getSubstituteMappingKey(),
                  cur.displayName(),
                  s.getSubstituteQuantity(),
                  s.getSubstituteUnit(),
                  cur.preparation(),
                  cur.optional()));
        }
      }
      if (s.getMethodOverlay() != null) {
        for (MethodOverlayLine ol : s.getMethodOverlay()) {
          boolean replaced = false;
          for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).stepNumber() == ol.step()) {
              steps.set(
                  i,
                  new CreateMethodStepRequest(
                      ol.step(), ol.instruction(), steps.get(i).durationMinutes()));
              replaced = true;
              break;
            }
          }
          if (!replaced) {
            log.warn(
                "Method overlay references non-existent step {} on substitution {}",
                ol.step(),
                s.getId());
          }
        }
      }
    }
    return new NewVersionInput(ings, steps, baseBody.metadata(), baseBody.tags());
  }
}
