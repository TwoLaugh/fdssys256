package com.example.mealprep.nutrition.api;

import com.example.mealprep.config.ProblemDetailSupport;
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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Nutrition-specific exception → {@link ProblemDetail} mapper. Annotated {@link
 * Order#HIGHEST_PRECEDENCE} so it fires before {@code GlobalExceptionHandler}'s
 * {@code @ExceptionHandler(Exception.class)} catch-all (which would otherwise swallow these into
 * 500s).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NutritionExceptionHandler {

  @ExceptionHandler(NutritionTargetsNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleTargetsNotFound(
      NutritionTargetsNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "nutrition-targets-not-found",
            "Nutrition targets not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(IntakeDayNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleIntakeDayNotFound(
      IntakeDayNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "intake-day-not-found",
            "Intake day not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(IntakeSlotNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleIntakeSlotNotFound(
      IntakeSlotNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "intake-slot-not-found",
            "Intake slot not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(IntakeSnackNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleIntakeSnackNotFound(
      IntakeSnackNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "intake-snack-not-found",
            "Intake snack not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(InvalidIntakeRangeException.class)
  public ResponseEntity<ProblemDetail> handleInvalidIntakeRange(
      InvalidIntakeRangeException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_REQUEST,
            ex.getMessage(),
            "invalid-intake-range",
            "Invalid intake range",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(JournalEntryNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleJournalEntryNotFound(
      JournalEntryNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "journal-entry-not-found",
            "Journal entry not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * Map the unique-constraint collision on {@code (user_id, on_date, meal_slot)} (slot-tied
   * journal-entry POST against an existing row) to HTTP 409. Lives here rather than in {@code
   * GlobalExceptionHandler} because the global advice is intentionally module-agnostic; this
   * mapping is generic-enough that promoting it later is a separate refactor.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
      DataIntegrityViolationException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            "A conflicting row already exists for this (user, date, mealSlot).",
            "journal-entry-conflict",
            "Conflict",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(IngredientMappingNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleIngredientMappingNotFound(
      IngredientMappingNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "ingredient-mapping-not-found",
            "Ingredient mapping not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(IngredientMappingPipelineException.class)
  public ResponseEntity<ProblemDetail> handleIngredientMappingPipeline(
      IngredientMappingPipelineException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "ingredient-mapping-pipeline",
            "Ingredient mapping pipeline failure",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  // ---------------- 01e: health-directive exceptions ----------------

  @ExceptionHandler(HealthDirectiveNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleHealthDirectiveNotFound(
      HealthDirectiveNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "health-directive-not-found",
            "Health directive not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(HealthDirectiveAlreadyDecidedException.class)
  public ResponseEntity<ProblemDetail> handleHealthDirectiveAlreadyDecided(
      HealthDirectiveAlreadyDecidedException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "health-directive-already-decided",
            "Health directive already decided",
            req.getRequestURI());
    pd.setProperty("currentStatus", ex.currentStatus());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(HealthDirectiveSafetyGateBlockedException.class)
  public ResponseEntity<ProblemDetail> handleHealthDirectiveSafetyGateBlocked(
      HealthDirectiveSafetyGateBlockedException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "health-directive-safety-gate-blocked",
            "Health directive blocked by safety gate",
            req.getRequestURI());
    pd.setProperty("findings", ex.findings());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(DuplicateHealthDirectiveException.class)
  public ResponseEntity<ProblemDetail> handleDuplicateHealthDirective(
      DuplicateHealthDirectiveException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "duplicate-health-directive",
            "Duplicate health directive",
            req.getRequestURI());
    pd.setProperty("existingDirectiveId", ex.existingDirectiveId());
    pd.setProperty("existingStatus", ex.existingStatus());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(InvalidDirectiveRoutingException.class)
  public ResponseEntity<ProblemDetail> handleInvalidDirectiveRouting(
      InvalidDirectiveRoutingException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "invalid-directive-routing",
            "Invalid directive routing",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(DirectiveApplyTargetUnavailableException.class)
  public ResponseEntity<ProblemDetail> handleDirectiveApplyTargetUnavailable(
      DirectiveApplyTargetUnavailableException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "directive-apply-target-unavailable",
            "Directive apply target unavailable",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  // ---------------- 01f: recipe-recalc exceptions ----------------

  @ExceptionHandler(RecipeVersionLookupFailedException.class)
  public ResponseEntity<ProblemDetail> handleRecipeVersionLookupFailed(
      RecipeVersionLookupFailedException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "recipe-version-lookup-failed",
            "Recipe version lookup failed",
            req.getRequestURI());
    pd.setProperty("recipeId", ex.recipeId());
    pd.setProperty("versionId", ex.versionId());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeNutritionWriteFailedException.class)
  public ResponseEntity<ProblemDetail> handleRecipeNutritionWriteFailed(
      RecipeNutritionWriteFailedException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "recipe-nutrition-write-failed",
            "Recipe nutrition write failed",
            req.getRequestURI());
    pd.setProperty("versionId", ex.versionId());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  // ---------------- 01g: floor-gate exceptions ----------------

  @ExceptionHandler(InvalidPlanRollupException.class)
  public ResponseEntity<ProblemDetail> handleInvalidPlanRollup(
      InvalidPlanRollupException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_REQUEST,
            ex.getMessage(),
            "invalid-plan-rollup",
            "Invalid plan rollup",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  // ---------------- 01h: weekly-aggregate + divergence exceptions ----------------

  @ExceptionHandler(InvalidWeekStartException.class)
  public ResponseEntity<ProblemDetail> handleInvalidWeekStart(
      InvalidWeekStartException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_REQUEST,
            ex.getMessage(),
            "invalid-week-start",
            "Invalid week start",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
