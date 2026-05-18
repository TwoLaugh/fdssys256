package com.example.mealprep.planner.domain.service.internal.stagec;

import com.example.mealprep.planner.api.dto.AugmentationProposal;
import java.util.Locale;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Converts the LLM's raw {@link AugmentationProposal} into the typed sealed {@link Augmentation}
 * hierarchy. Per ticket planner-01h §"{@code AugmentationParser}" and lld/planner.md line 1342:
 * malformed proposals are dropped <b>silently</b> (returns {@code null}) — never throws. The LLM
 * occasionally emits incomplete output and the silent-drop policy keeps Phase 2 resilient. Callers
 * MUST filter the {@code null}s out ({@code .filter(Objects::nonNull)}).
 */
@Component
class AugmentationParser {

  /**
   * Parse one proposal into a typed augmentation, or {@code null} if the {@code type} is unknown or
   * a required field for that type is missing. {@code type} matching is case-insensitive.
   */
  @Nullable
  Augmentation parse(@Nullable AugmentationProposal p) {
    if (p == null) {
      return null;
    }
    String type = p.type() == null ? "" : p.type().toUpperCase(Locale.ROOT);
    return switch (type) {
      case "ADD_SNACK" -> {
        if (p.newRecipeId() == null || p.servings() == null) {
          yield null;
        }
        yield new AddSnackAugmentation(
            p.targetSlotId(), p.newRecipeId(), p.servings(), p.reasoning());
      }
      case "INGREDIENT_SWAP" -> {
        if (p.fromIngredientKey() == null || p.toIngredientKey() == null) {
          yield null;
        }
        yield new IngredientSwapAugmentation(
            p.targetSlotId(), p.fromIngredientKey(), p.toIngredientKey(), p.reasoning());
      }
      case "REPAIR" -> {
        if (p.issue() == null || p.resolution() == null) {
          yield null;
        }
        yield new RepairAugmentation(p.targetSlotId(), p.issue(), p.resolution());
      }
      default -> null;
    };
  }
}
