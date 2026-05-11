package com.example.mealprep.recipe.testdata;

import com.example.mealprep.recipe.api.dto.AcceptSubstitutionRequest;
import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.CreateBranchRequest;
import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeBodyRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import com.example.mealprep.recipe.api.dto.CreateSubstitutionRequest;
import com.example.mealprep.recipe.api.dto.MethodOverlayLineRequest;
import com.example.mealprep.recipe.api.dto.PromoteSubstitutionRequest;
import com.example.mealprep.recipe.api.dto.RejectSubstitutionRequest;
import com.example.mealprep.recipe.api.dto.RevertToVersionRequest;
import com.example.mealprep.recipe.api.dto.SubstitutionItemRequest;
import com.example.mealprep.recipe.api.dto.SubstitutionReason;
import com.example.mealprep.recipe.api.dto.UpdateRecipeManualEditRequest;
import com.example.mealprep.recipe.domain.entity.Complexity;
import com.example.mealprep.recipe.spi.SaveAdaptedBranchCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedSubstitutionCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Test Data Builder for the recipe module. Defaults pass all 01a validators so callers tweak only
 * the field under test.
 */
public final class RecipeTestData {

  private RecipeTestData() {}

  public static CreateRecipeRequest defaultCreateRequest() {
    return new CreateRecipeRequest(
        "Spaghetti Bolognese",
        "Hearty weeknight pasta.",
        defaultIngredients(),
        defaultMethod(),
        defaultMetadata(),
        defaultTags());
  }

  public static CreateRecipeRequest createRequestWithName(String name) {
    return new CreateRecipeRequest(
        name,
        "Hearty weeknight pasta.",
        defaultIngredients(),
        defaultMethod(),
        defaultMetadata(),
        defaultTags());
  }

  public static CreateRecipeRequest createRequestWithoutTags() {
    return new CreateRecipeRequest(
        "Spaghetti Bolognese",
        "Hearty weeknight pasta.",
        defaultIngredients(),
        defaultMethod(),
        defaultMetadata(),
        null);
  }

  public static List<CreateIngredientRequest> defaultIngredients() {
    return List.of(
        new CreateIngredientRequest(
            0, "spaghetti.dry", "Spaghetti", new BigDecimal("400.000"), "g", null, false),
        new CreateIngredientRequest(
            1, "beef.mince", "Lean beef mince", new BigDecimal("500.000"), "g", null, false),
        new CreateIngredientRequest(
            2, "tomato.passata", "Passata", new BigDecimal("700.000"), "g", null, false));
  }

  public static List<CreateMethodStepRequest> defaultMethod() {
    return List.of(
        new CreateMethodStepRequest(1, "Brown the mince in a wide pan.", 8),
        new CreateMethodStepRequest(2, "Add passata and simmer for 25 minutes.", 25),
        new CreateMethodStepRequest(3, "Cook spaghetti to al dente; drain.", 9));
  }

  public static CreateRecipeMetadataRequest defaultMetadata() {
    return new CreateRecipeMetadataRequest(
        4, 15, 30, 45, List.of("large pan", "colander"), 3, 2, true, "Italian", List.of("DINNER"));
  }

  public static CreateRecipeTagsRequest defaultTags() {
    return new CreateRecipeTagsRequest(
        "beef", "stovetop", Complexity.MODERATE, List.of("savoury", "umami"), List.of());
  }

  /**
   * Default manual-edit request — same body as the 01a default create request, but with the second
   * method step's duration bumped from 25 to 35 minutes (so the diff is non-empty), {@code
   * changeReason} populated, and {@code expectedOptimisticVersion} taken from the caller.
   */
  public static UpdateRecipeManualEditRequest defaultManualEditRequest(
      long expectedOptimisticVersion) {
    List<CreateMethodStepRequest> editedMethod =
        List.of(
            new CreateMethodStepRequest(1, "Brown the mince in a wide pan.", 8),
            new CreateMethodStepRequest(2, "Add passata and simmer for 35 minutes.", 35),
            new CreateMethodStepRequest(3, "Cook spaghetti to al dente; drain.", 9));
    return new UpdateRecipeManualEditRequest(
        "Spaghetti Bolognese",
        "Hearty weeknight pasta.",
        defaultIngredients(),
        editedMethod,
        defaultMetadata(),
        defaultTags(),
        "Simmer longer for deeper flavour.",
        expectedOptimisticVersion);
  }

  /** Same body as {@link #defaultCreateRequest()}, mapped into a manual-edit request shape. */
  public static UpdateRecipeManualEditRequest noopManualEditRequest(
      long expectedOptimisticVersion) {
    return new UpdateRecipeManualEditRequest(
        "Spaghetti Bolognese",
        "Hearty weeknight pasta.",
        defaultIngredients(),
        defaultMethod(),
        defaultMetadata(),
        defaultTags(),
        "Should reject as no-op.",
        expectedOptimisticVersion);
  }

  /** Recipe-body sub-block for a branch creation request — defaults are valid. */
  public static CreateRecipeBodyRequest defaultBranchBody() {
    return new CreateRecipeBodyRequest(
        defaultIngredients(), defaultMethod(), defaultMetadata(), defaultTags());
  }

  /**
   * Default branch creation request with {@code name = "gluten-free-variant"}, no fingerprint
   * override (server derives), and the default body.
   */
  public static CreateBranchRequest defaultCreateBranchRequest(UUID branchPointVersionId) {
    return new CreateBranchRequest(
        "gluten-free-variant",
        "Gluten-free variant",
        "Replace pasta with gluten-free alternative.",
        branchPointVersionId,
        defaultBranchBody(),
        null);
  }

  /** Branch creation request with the supplied name. */
  public static CreateBranchRequest branchRequestWithName(String name, UUID branchPointVersionId) {
    return new CreateBranchRequest(
        name, null, "Forked from main.", branchPointVersionId, defaultBranchBody(), null);
  }

  /** Branch creation request carrying a fingerprint override (skips server derivation). */
  public static CreateBranchRequest branchRequestWithOverride(
      UUID branchPointVersionId, CharacterFingerprintDto override) {
    return new CreateBranchRequest(
        "spicy-variant",
        "Spicy variant",
        "Add chilli.",
        branchPointVersionId,
        defaultBranchBody(),
        override);
  }

  public static CharacterFingerprintDto defaultFingerprint() {
    return new CharacterFingerprintDto(
        List.of("Spaghetti", "Lean beef mince", "Passata"),
        List.of(),
        List.of(),
        List.of("savoury", "umami"),
        Complexity.MODERATE,
        "Italian");
  }

  /** Revert request to a given branchId / versionNumber / optimistic-version. */
  public static RevertToVersionRequest revertRequest(
      UUID branchId, int versionNumber, long expectedRecipeOptimisticVersion) {
    return new RevertToVersionRequest(branchId, versionNumber, expectedRecipeOptimisticVersion);
  }

  /**
   * Substitution proposal swapping {@code beef.mince} (from {@link #defaultIngredients()}) for
   * {@code soy.crumble}, no method overlay, no notes, {@code temporary = true}.
   */
  public static CreateSubstitutionRequest defaultSubstitutionRequest(UUID versionId) {
    return new CreateSubstitutionRequest(
        versionId,
        new SubstitutionItemRequest("beef.mince", new BigDecimal("500.000"), "g"),
        new SubstitutionItemRequest("soy.crumble", new BigDecimal("400.000"), "g"),
        SubstitutionReason.DIETARY_TEMP,
        null,
        null,
        null,
        true);
  }

  /** Substitution proposal with an explicit single method overlay line at step 2. */
  public static CreateSubstitutionRequest substitutionRequestWithMethodOverlay(UUID versionId) {
    return new CreateSubstitutionRequest(
        versionId,
        new SubstitutionItemRequest("beef.mince", new BigDecimal("500.000"), "g"),
        new SubstitutionItemRequest("soy.crumble", new BigDecimal("400.000"), "g"),
        SubstitutionReason.DIETARY_TEMP,
        null,
        List.of(new MethodOverlayLineRequest(2, "Add passata and simmer for 20 minutes.")),
        null,
        true);
  }

  /** Accept request carrying the supplied optimistic version. */
  public static AcceptSubstitutionRequest acceptRequest(long expectedVersion) {
    return new AcceptSubstitutionRequest(expectedVersion);
  }

  /** Reject request carrying the supplied optimistic version + optional reason. */
  public static RejectSubstitutionRequest rejectRequest(long expectedVersion, String reason) {
    return new RejectSubstitutionRequest(expectedVersion, reason);
  }

  /** Promote-to-version request. */
  public static PromoteSubstitutionRequest promoteRequest(
      long expectedVersion, String changeReason) {
    return new PromoteSubstitutionRequest(expectedVersion, changeReason);
  }

  // ---------------- recipe-01f: RecipeWriteApi commands ----------------

  /** Adaptation-pipeline new-version command targeting the supplied parent head. */
  public static SaveAdaptedVersionCommand defaultSaveAdaptedVersionCommand(
      UUID recipeId,
      UUID branchId,
      int expectedParentVersionNumber,
      UUID expectedParentVersionId,
      UUID adapterTraceId) {
    return new SaveAdaptedVersionCommand(
        recipeId,
        branchId,
        expectedParentVersionNumber,
        expectedParentVersionId,
        defaultIngredients(),
        defaultMethod(),
        defaultMetadata(),
        defaultTags(),
        defaultFingerprint(),
        JsonNodeFactory.instance.objectNode(),
        "Adapter swapped passata for fresh tomato puree.",
        adapterTraceId);
  }

  /** Adaptation-pipeline new-branch command rooted at the supplied branch-point version. */
  public static SaveAdaptedBranchCommand defaultSaveAdaptedBranchCommand(
      UUID recipeId, UUID parentBranchId, UUID branchPointVersionId, UUID adapterTraceId) {
    return new SaveAdaptedBranchCommand(
        recipeId,
        parentBranchId,
        branchPointVersionId,
        "adapter-low-sodium",
        "Adapter low-sodium variant",
        "Reduce sodium for dietary constraint.",
        defaultIngredients(),
        defaultMethod(),
        defaultMetadata(),
        defaultTags(),
        defaultFingerprint(),
        adapterTraceId);
  }

  /** Adaptation-pipeline new-substitution command on the supplied version. */
  public static SaveAdaptedSubstitutionCommand defaultSaveAdaptedSubstitutionCommand(
      UUID recipeId, UUID versionId, UUID adapterTraceId) {
    return new SaveAdaptedSubstitutionCommand(
        recipeId,
        versionId,
        new SubstitutionItemRequest("beef.mince", new BigDecimal("500.000"), "g"),
        new SubstitutionItemRequest("soy.crumble", new BigDecimal("400.000"), "g"),
        SubstitutionReason.DIETARY_TEMP,
        null,
        null,
        null,
        true,
        adapterTraceId);
  }
}
