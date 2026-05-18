package com.example.mealprep.planner.domain.service.internal.stagec;

import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Post-hoc allergen-safety gate for Phase-2 augmentations. Per lld/planner.md §{@code
 * Phase2Augmenter} line 917 and ticket planner-01h §"{@code AugmentationVerifier}": the LLM is
 * never trusted to remember constraints, so every proposed augmentation re-runs the <i>same</i>
 * {@link HardConstraintFilterService} the Stage-A hard filter uses.
 *
 * <p>Thin wrapper — the constraint logic lives in {@code HardConstraintFilterService}
 * (preference-01b). Scope is deliberately allergen safety + time budget only; semantic correctness
 * of an ingredient swap (does the recipe still make sense?) is owned by recipe-01e and the
 * adaptation pipeline, not Phase 2. Per-slot routing mirrors the Stage-A hard filter: a {@code
 * shared} slot uses {@code checkForHousehold(eaters,...)}; a per-person slot loops {@code
 * check(userId,...)}. When no slot skeleton matches the target id (plan-level / Repair) the check
 * falls back to the household union across every eater in the context.
 *
 * <p>Failures are logged WARN with the augmentation type and the reason; the augmenter discards
 * them silently from the user's perspective.
 */
@Component
class AugmentationVerifier {

  private static final Logger log = LoggerFactory.getLogger(AugmentationVerifier.class);

  private final HardConstraintFilterService hardConstraintFilterService;
  private final PlannerProperties properties;

  AugmentationVerifier(
      HardConstraintFilterService hardConstraintFilterService, PlannerProperties properties) {
    this.hardConstraintFilterService = hardConstraintFilterService;
    this.properties = properties;
  }

  /**
   * @return {@code true} if the augmentation is safe to apply; {@code false} (logged WARN) if it
   *     hallucinated a recipe, clashes with a hard constraint, or blows the slot's time budget.
   */
  boolean passes(Augmentation aug, PlanCompositionContext ctx) {
    // Java 17: switch-pattern is still a preview feature; use stable instanceof pattern matching.
    // The sealed hierarchy keeps this exhaustive — a new permit forces a new branch here.
    if (aug instanceof AddSnackAugmentation a) {
      return verifyAddSnack(a, ctx);
    }
    if (aug instanceof IngredientSwapAugmentation s) {
      return verifyIngredientSwap(s, ctx);
    }
    if (aug instanceof RepairAugmentation) {
      return true; // informational flag — no constraint to verify
    }
    throw new IllegalStateException("Unhandled Augmentation subtype: " + aug);
  }

  private boolean verifyAddSnack(AddSnackAugmentation a, PlanCompositionContext ctx) {
    RecipeDto recipe = findInPool(ctx, a.newRecipeId());
    if (recipe == null) {
      log.warn(
          "Phase 2 verifier: ADD_SNACK rejected — recipeId {} not in recipe pool"
              + " (LLM hallucination)",
          a.newRecipeId());
      return false;
    }
    List<String> ingredientKeys = ingredientKeys(recipe);
    Optional<MealSlotSkeleton> slot = findSlot(ctx, a.targetSlotId());

    if (!constraintsPass(ctx, slot, ingredientKeys)) {
      log.warn(
          "Phase 2 verifier: ADD_SNACK rejected — recipeId {} clashes with a hard constraint",
          a.newRecipeId());
      return false;
    }
    if (slot.isPresent() && exceedsTimeBudget(recipe, slot.get())) {
      log.warn(
          "Phase 2 verifier: ADD_SNACK rejected — recipeId {} exceeds slot {} time budget",
          a.newRecipeId(),
          a.targetSlotId());
      return false;
    }
    return true;
  }

  private boolean verifyIngredientSwap(IngredientSwapAugmentation s, PlanCompositionContext ctx) {
    Optional<MealSlotSkeleton> slot = findSlot(ctx, s.targetSlotId());
    // Validate only the swapped-in ingredient's allergen safety against the slot's eaters; the
    // swap is a tag-only carrier in 01h (no recipe mutation).
    if (!constraintsPass(ctx, slot, List.of(s.toIngredientKey()))) {
      log.warn(
          "Phase 2 verifier: INGREDIENT_SWAP rejected — toIngredientKey '{}' clashes with a"
              + " hard constraint",
          s.toIngredientKey());
      return false;
    }
    return true;
  }

  /**
   * Run the hard-constraint filter. {@code shared} slot → household union over the slot's eaters;
   * per-person slot → per-eater {@code check} loop (fails on the first violation). No matching slot
   * (plan-level) → household union over every eater in the context.
   */
  private boolean constraintsPass(
      PlanCompositionContext ctx, Optional<MealSlotSkeleton> slot, List<String> ingredientKeys) {
    if (slot.isEmpty()) {
      List<UUID> allEaters =
          ctx.slotSkeletons() == null
              ? List.of()
              : ctx.slotSkeletons().stream()
                  .flatMap(
                      sk -> sk.eaters() == null ? List.<UUID>of().stream() : sk.eaters().stream())
                  .distinct()
                  .toList();
      if (allEaters.isEmpty()) {
        return true; // nothing to check against — never block on missing data
      }
      return hardConstraintFilterService.checkForHousehold(allEaters, ingredientKeys).passes();
    }
    MealSlotSkeleton sk = slot.get();
    List<UUID> eaters = sk.eaters() == null ? List.of() : sk.eaters();
    if (eaters.isEmpty()) {
      return true;
    }
    if (sk.shared()) {
      return hardConstraintFilterService.checkForHousehold(eaters, ingredientKeys).passes();
    }
    for (UUID userId : eaters) {
      FilterResult r = hardConstraintFilterService.check(userId, ingredientKeys);
      if (!r.passes()) {
        return false;
      }
    }
    return true;
  }

  private boolean exceedsTimeBudget(RecipeDto recipe, MealSlotSkeleton slot) {
    int totalTime =
        recipe.currentVersionBody() != null && recipe.currentVersionBody().metadata() != null
            ? recipe.currentVersionBody().metadata().totalTimeMins()
            : 0;
    BigDecimal limit =
        BigDecimal.valueOf(slot.timeBudgetMin())
            .multiply(properties.maxTimeOvershootRatio())
            .setScale(0, RoundingMode.FLOOR);
    return BigDecimal.valueOf(totalTime).compareTo(limit) > 0;
  }

  private static RecipeDto findInPool(PlanCompositionContext ctx, UUID recipeId) {
    if (ctx.recipePool() == null || ctx.recipePool().recipes() == null || recipeId == null) {
      return null;
    }
    return ctx.recipePool().recipes().stream()
        .filter(r -> recipeId.equals(r.id()))
        .findFirst()
        .orElse(null);
  }

  private static Optional<MealSlotSkeleton> findSlot(PlanCompositionContext ctx, UUID slotId) {
    if (slotId == null || ctx.slotSkeletons() == null) {
      return Optional.empty();
    }
    return ctx.slotSkeletons().stream().filter(s -> slotId.equals(s.slotId())).findFirst();
  }

  private static List<String> ingredientKeys(RecipeDto recipe) {
    if (recipe.currentVersionBody() == null || recipe.currentVersionBody().ingredients() == null) {
      return List.of();
    }
    return recipe.currentVersionBody().ingredients().stream()
        .map(IngredientDto::ingredientMappingKey)
        .filter(k -> k != null && !k.isBlank())
        .toList();
  }
}
