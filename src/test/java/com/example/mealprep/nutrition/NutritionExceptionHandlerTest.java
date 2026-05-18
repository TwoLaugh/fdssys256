package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.NutritionExceptionHandler;
import com.example.mealprep.nutrition.api.dto.DirectiveStatus;
import com.example.mealprep.nutrition.api.dto.SafetyFindingDto;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.exception.DirectiveApplyTargetUnavailableException;
import com.example.mealprep.nutrition.exception.DuplicateHealthDirectiveException;
import com.example.mealprep.nutrition.exception.HealthDirectiveAlreadyDecidedException;
import com.example.mealprep.nutrition.exception.HealthDirectiveNotFoundException;
import com.example.mealprep.nutrition.exception.HealthDirectiveSafetyGateBlockedException;
import com.example.mealprep.nutrition.exception.IngredientMappingNotFoundException;
import com.example.mealprep.nutrition.exception.IngredientMappingPipelineException;
import com.example.mealprep.nutrition.exception.IntakeDayNotFoundException;
import com.example.mealprep.nutrition.exception.IntakeSlotNotFoundException;
import com.example.mealprep.nutrition.exception.IntakeSnackNotFoundException;
import com.example.mealprep.nutrition.exception.InvalidDirectiveRoutingException;
import com.example.mealprep.nutrition.exception.InvalidIntakeRangeException;
import com.example.mealprep.nutrition.exception.InvalidPlanRollupException;
import com.example.mealprep.nutrition.exception.InvalidWeekStartException;
import com.example.mealprep.nutrition.exception.JournalEntryNotFoundException;
import com.example.mealprep.nutrition.exception.NutritionTargetsNotFoundException;
import com.example.mealprep.nutrition.exception.RecipeNutritionWriteFailedException;
import com.example.mealprep.nutrition.exception.RecipeVersionLookupFailedException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link NutritionExceptionHandler}. Each handler is invoked directly with its
 * exception + a mock request; assertions pin the HTTP status, the {@code application/problem+json}
 * content type, and the ProblemDetail detail / title / type-slug / instance plus any extension
 * properties. This kills the per-handler status-constant, string-literal and {@code setProperty}
 * mutants (all 26 were previously uncovered). Pure — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class NutritionExceptionHandlerTest {

  private static final String URI = "/api/v1/nutrition/whatever";

  @Mock private HttpServletRequest request;
  private final NutritionExceptionHandler handler = new NutritionExceptionHandler();

  @BeforeEach
  void stubUri() {
    when(request.getRequestURI()).thenReturn(URI);
  }

  private void assertProblem(
      ResponseEntity<ProblemDetail> resp,
      HttpStatus status,
      String typeSlug,
      String title,
      String detailContains) {
    assertThat(resp.getStatusCode()).isEqualTo(status);
    assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail pd = resp.getBody();
    assertThat(pd).isNotNull();
    assertThat(pd.getStatus()).isEqualTo(status.value());
    assertThat(pd.getTitle()).isEqualTo(title);
    assertThat(pd.getType().toString())
        .isEqualTo("https://mealprep.example.com/problems/" + typeSlug);
    assertThat(pd.getInstance().toString()).isEqualTo(URI);
    assertThat(pd.getDetail()).contains(detailContains);
  }

  @Test
  void targetsNotFound_maps_to_404() {
    UUID userId = UUID.randomUUID();
    var resp =
        handler.handleTargetsNotFound(new NutritionTargetsNotFoundException(userId), request);
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "nutrition-targets-not-found",
        "Nutrition targets not found",
        userId.toString());
  }

  @Test
  void intakeDayNotFound_maps_to_404() {
    var resp =
        handler.handleIntakeDayNotFound(
            new IntakeDayNotFoundException(UUID.randomUUID(), LocalDate.of(2026, 5, 18)), request);
    assertProblem(
        resp, HttpStatus.NOT_FOUND, "intake-day-not-found", "Intake day not found", "2026-05-18");
  }

  @Test
  void intakeSlotNotFound_maps_to_404() {
    var resp =
        handler.handleIntakeSlotNotFound(
            new IntakeSlotNotFoundException(
                UUID.randomUUID(), LocalDate.of(2026, 5, 18), MealSlot.LUNCH),
            request);
    assertProblem(
        resp, HttpStatus.NOT_FOUND, "intake-slot-not-found", "Intake slot not found", "LUNCH");
  }

  @Test
  void intakeSnackNotFound_maps_to_404() {
    UUID snackId = UUID.randomUUID();
    var resp =
        handler.handleIntakeSnackNotFound(
            new IntakeSnackNotFoundException(UUID.randomUUID(), LocalDate.of(2026, 5, 18), snackId),
            request);
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "intake-snack-not-found",
        "Intake snack not found",
        snackId.toString());
  }

  @Test
  void invalidIntakeRange_maps_to_400() {
    var resp =
        handler.handleInvalidIntakeRange(new InvalidIntakeRangeException("from after to"), request);
    assertProblem(
        resp,
        HttpStatus.BAD_REQUEST,
        "invalid-intake-range",
        "Invalid intake range",
        "from after to");
  }

  @Test
  void journalEntryNotFound_maps_to_404() {
    UUID entryId = UUID.randomUUID();
    var resp =
        handler.handleJournalEntryNotFound(new JournalEntryNotFoundException(entryId), request);
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "journal-entry-not-found",
        "Journal entry not found",
        entryId.toString());
  }

  @Test
  void dataIntegrityViolation_maps_to_409_with_fixed_detail() {
    var resp =
        handler.handleDataIntegrityViolation(
            new DataIntegrityViolationException("dup key"), request);
    assertProblem(
        resp,
        HttpStatus.CONFLICT,
        "journal-entry-conflict",
        "Conflict",
        "conflicting row already exists");
  }

  @Test
  void ingredientMappingNotFound_maps_to_404() {
    var resp =
        handler.handleIngredientMappingNotFound(
            new IngredientMappingNotFoundException("quinoa"), request);
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "ingredient-mapping-not-found",
        "Ingredient mapping not found",
        "quinoa");
  }

  @Test
  void ingredientMappingPipeline_maps_to_422() {
    var resp =
        handler.handleIngredientMappingPipeline(
            new IngredientMappingPipelineException("usda down"), request);
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "ingredient-mapping-pipeline",
        "Ingredient mapping pipeline failure",
        "usda down");
  }

  @Test
  void healthDirectiveNotFound_maps_to_404() {
    UUID id = UUID.randomUUID();
    var resp =
        handler.handleHealthDirectiveNotFound(new HealthDirectiveNotFoundException(id), request);
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "health-directive-not-found",
        "Health directive not found",
        id.toString());
  }

  @Test
  void healthDirectiveAlreadyDecided_maps_to_409_with_currentStatus_property() {
    var resp =
        handler.handleHealthDirectiveAlreadyDecided(
            new HealthDirectiveAlreadyDecidedException(UUID.randomUUID(), DirectiveStatus.ACCEPTED),
            request);
    assertProblem(
        resp,
        HttpStatus.CONFLICT,
        "health-directive-already-decided",
        "Health directive already decided",
        "");
    assertThat(resp.getBody().getProperties())
        .containsEntry("currentStatus", DirectiveStatus.ACCEPTED);
  }

  @Test
  void healthDirectiveSafetyGateBlocked_maps_to_422_with_findings_property() {
    List<SafetyFindingDto> findings = List.of(new SafetyFindingDto("X1", "danger", "HIGH"));
    var resp =
        handler.handleHealthDirectiveSafetyGateBlocked(
            new HealthDirectiveSafetyGateBlockedException(UUID.randomUUID(), findings), request);
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "health-directive-safety-gate-blocked",
        "Health directive blocked by safety gate",
        "");
    assertThat(resp.getBody().getProperties()).containsEntry("findings", findings);
  }

  @Test
  void duplicateHealthDirective_maps_to_409_with_existing_properties() {
    UUID existing = UUID.randomUUID();
    var resp =
        handler.handleDuplicateHealthDirective(
            new DuplicateHealthDirectiveException(existing, DirectiveStatus.PENDING_REVIEW),
            request);
    assertProblem(
        resp, HttpStatus.CONFLICT, "duplicate-health-directive", "Duplicate health directive", "");
    assertThat(resp.getBody().getProperties())
        .containsEntry("existingDirectiveId", existing)
        .containsEntry("existingStatus", DirectiveStatus.PENDING_REVIEW);
  }

  @Test
  void invalidDirectiveRouting_maps_to_422() {
    var resp =
        handler.handleInvalidDirectiveRouting(
            new InvalidDirectiveRoutingException("galaxy_model"), request);
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "invalid-directive-routing",
        "Invalid directive routing",
        "galaxy_model");
  }

  @Test
  void directiveApplyTargetUnavailable_maps_to_422() {
    var resp =
        handler.handleDirectiveApplyTargetUnavailable(
            new DirectiveApplyTargetUnavailableException("preference module not wired"), request);
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "directive-apply-target-unavailable",
        "Directive apply target unavailable",
        "preference module not wired");
  }

  @Test
  void recipeVersionLookupFailed_maps_to_404_with_recipe_and_version_properties() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    var resp =
        handler.handleRecipeVersionLookupFailed(
            new RecipeVersionLookupFailedException(recipeId, versionId), request);
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "recipe-version-lookup-failed",
        "Recipe version lookup failed",
        "");
    assertThat(resp.getBody().getProperties())
        .containsEntry("recipeId", recipeId)
        .containsEntry("versionId", versionId);
  }

  @Test
  void recipeNutritionWriteFailed_maps_to_422_with_version_property() {
    UUID versionId = UUID.randomUUID();
    var resp =
        handler.handleRecipeNutritionWriteFailed(
            new RecipeNutritionWriteFailedException(versionId, "writer rejected"), request);
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "recipe-nutrition-write-failed",
        "Recipe nutrition write failed",
        "writer rejected");
    assertThat(resp.getBody().getProperties()).containsEntry("versionId", versionId);
  }

  @Test
  void invalidPlanRollup_maps_to_400() {
    var resp =
        handler.handleInvalidPlanRollup(
            new InvalidPlanRollupException("rollup days mismatch"), request);
    assertProblem(
        resp,
        HttpStatus.BAD_REQUEST,
        "invalid-plan-rollup",
        "Invalid plan rollup",
        "rollup days mismatch");
  }

  @Test
  void invalidWeekStart_maps_to_400() {
    var resp =
        handler.handleInvalidWeekStart(new InvalidWeekStartException(DayOfWeek.WEDNESDAY), request);
    assertProblem(resp, HttpStatus.BAD_REQUEST, "invalid-week-start", "Invalid week start", "");
  }
}
