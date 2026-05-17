package com.example.mealprep.feedback.api.dto;

import com.example.mealprep.feedback.spi.Destination;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/feedback/clarifications/{queryId}/answer}. The user either
 * picks one of the four destinations, types free-text refinement, or both — the re-classifier sees
 * whatever is supplied. Per lld/feedback.md lines 342-351.
 *
 * <p>The {@code @AssertTrue} validator is annotated {@link JsonIgnore} (wave-3 retro gotcha):
 * without it Jackson serialises {@code isAtLeastOneProvided()} as a {@code "atLeastOneProvided":
 * ...} boolean property which the strict OpenAPI request-body validator in the ITs rejects as an
 * unexpected field.
 */
public record AnswerClarificationRequest(
    Destination selectedDestination, @Size(max = 4000) String userClarificationText) {

  @JsonIgnore
  @AssertTrue(message = "Either selectedDestination or userClarificationText must be provided")
  public boolean isAtLeastOneProvided() {
    return selectedDestination != null
        || (userClarificationText != null && !userClarificationText.isBlank());
  }
}
