package com.example.mealprep.recipe.testdata;

import com.example.mealprep.recipe.api.dto.CreateRatingRequest;
import com.example.mealprep.recipe.api.dto.UpdateRatingRequest;
import java.util.UUID;

/**
 * Test Data Builder for recipe-02b ratings. Defaults pass all validators; callers tweak the field
 * under test.
 */
public final class RecipeRatingTestData {

  private RecipeRatingTestData() {}

  /** Detailed rating: all four dimensions populated. */
  public static CreateRatingRequest detailedCreateRequest(UUID versionId) {
    return new CreateRatingRequest(versionId, null, 85, 70, 90, 75, "Great with extra chilli.");
  }

  /** One-tap rating: only taste. */
  public static CreateRatingRequest oneTapCreateRequest(UUID versionId, int taste) {
    return new CreateRatingRequest(versionId, null, taste, null, null, null, null);
  }

  public static CreateRatingRequest createRequest(
      UUID versionId,
      Integer taste,
      Integer effortWorthIt,
      Integer portionFit,
      Integer repeatValue) {
    return new CreateRatingRequest(
        versionId, null, taste, effortWorthIt, portionFit, repeatValue, null);
  }

  public static UpdateRatingRequest updateRequest(UUID versionId, int taste, long expectedVersion) {
    return new UpdateRatingRequest(versionId, null, taste, null, null, null, null, expectedVersion);
  }

  public static UpdateRatingRequest detailedUpdateRequest(
      UUID versionId,
      int taste,
      Integer effortWorthIt,
      Integer portionFit,
      Integer repeatValue,
      long expectedVersion) {
    return new UpdateRatingRequest(
        versionId, null, taste, effortWorthIt, portionFit, repeatValue, null, expectedVersion);
  }
}
