package com.example.mealprep.adaptation.ai;

import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.service.NutritionalKnowledgeService;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the typed {@link AdaptationContext} from an {@link AdaptationJob} + the trigger-specific
 * {@link TriggerInputs}. Separates context-loading (calling peer-module query services) from task
 * dispatch — 01c shipped a minimal inline {@code loadContextPlaceholder}; 01e extracts the real
 * loader here (LLD line 868, ticket step 7).
 *
 * <p><b>LLD-shape reconciliation</b>: the ticket pseudocode assembled a {@code recipeSummary} view
 * and pulled {@code SoftPreferenceSummary} / {@code NutritionTargetsSummary} from peer services
 * that do not yet exist on the merged contracts. The actual merged {@link AdaptationContext} record
 * carries {@link RecipeDto} + {@link RecipeVersionDto} + {@link CharacterFingerprintDto} plus
 * string {@code softPreferencesHash} / {@code nutritionTargetsSummary} placeholders. This assembler
 * targets the real record: it loads the recipe + current version + fingerprint via {@link
 * RecipeQueryService}, derives a stable {@code hardConstraintsHash} (prompt-cache stability) from
 * {@link PreferenceQueryService#getHardConstraints}, and pulls the food-science bundle via {@link
 * NutritionalKnowledgeService#lookupForRecipe}. Soft-preference and nutrition-target summaries stay
 * as the placeholder strings 01c introduced until those peer query seams land.
 */
@Component
public class AdaptationContextAssembler {

  private final RecipeQueryService recipeQueryService;
  private final PreferenceQueryService preferenceQueryService;
  private final NutritionalKnowledgeService nutritionalKnowledgeService;

  public AdaptationContextAssembler(
      RecipeQueryService recipeQueryService,
      PreferenceQueryService preferenceQueryService,
      NutritionalKnowledgeService nutritionalKnowledgeService) {
    this.recipeQueryService = recipeQueryService;
    this.preferenceQueryService = preferenceQueryService;
    this.nutritionalKnowledgeService = nutritionalKnowledgeService;
  }

  /**
   * Assemble the dispatch context. {@code candidates} is appended later by the worker via {@link
   * AdaptationContext#withCandidates}; pass an empty list at load time.
   */
  @Transactional(readOnly = true)
  public AdaptationContext assemble(
      AdaptationJob job, List<AdaptationCandidateDto> candidates, TriggerInputs triggerInputs) {
    Optional<RecipeDto> recipeOpt = recipeQueryService.getById(job.getRecipeId());
    RecipeDto recipe = recipeOpt.orElse(null);
    RecipeVersionDto currentVersion = recipe == null ? null : recipe.currentVersionBody();

    CharacterFingerprintDto fingerprint = null;
    if (recipe != null && recipe.currentBranchId() != null) {
      fingerprint =
          recipeQueryService
              .getFingerprint(job.getRecipeId(), recipe.currentBranchId())
              .orElse(null);
    }

    List<String> mappingKeys = extractMappingKeys(currentVersion);
    NutritionalKnowledgeBundleDto knowledge =
        nutritionalKnowledgeService.lookupForRecipe(
            currentVersion == null ? null : currentVersion.id(), mappingKeys);

    String hardConstraintsHash = hashHardConstraints(job.getUserId());

    return new AdaptationContext(
        mapMode(job.getSource()),
        recipe,
        currentVersion,
        fingerprint,
        candidates == null ? List.of() : List.copyOf(candidates),
        null, // softPreferencesHash — peer soft-bundle seam not yet available (01c placeholder)
        hardConstraintsHash,
        null, // nutritionTargetsSummary — peer targets-summary seam not yet available
        knowledge,
        triggerInputs == null ? null : triggerInputs.feedbackText(),
        triggerInputs == null ? null : triggerInputs.ratingDelta(),
        triggerInputs == null ? null : triggerInputs.directive(),
        triggerInputs == null ? null : triggerInputs.dataModelChange());
  }

  /** Ingredient mapping keys drive the food-science lookup; absent body → empty. */
  static List<String> extractMappingKeys(RecipeVersionDto version) {
    if (version == null || version.ingredients() == null) {
      return List.of();
    }
    return version.ingredients().stream()
        .map(IngredientDto::ingredientMappingKey)
        .filter(k -> k != null && !k.isBlank())
        .distinct()
        .toList();
  }

  /**
   * Stable hash of the user's hard constraints — keeps the LLM prompt-cache key stable across calls
   * for an unchanged constraint set (LLD line 835 / ticket step 7 "for prompt-cache stability").
   * Falls back to a fixed sentinel when the user has no aggregate yet.
   */
  String hashHardConstraints(UUID userId) {
    Optional<HardConstraintsDto> hc = preferenceQueryService.getHardConstraints(userId);
    if (hc.isEmpty()) {
      return "hc:none";
    }
    return "hc:" + Integer.toHexString(hc.get().hashCode());
  }

  /** Maps the job source to the prompt-facing {@code mode} string (LLD line 832). */
  static String mapMode(JobSource source) {
    return switch (source) {
      case IMPORT -> "IMPORT";
      case FEEDBACK -> "FEEDBACK";
      case DATA_MODEL_CHANGE -> "DATA_MODEL_CHANGE";
      case PLAN_TIME -> "PLAN_TIME_REFINE";
    };
  }
}
