package com.example.mealprep.recipe.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.recipe.exception.NoChangesException;
import com.example.mealprep.recipe.exception.RecipeAccessDeniedException;
import com.example.mealprep.recipe.exception.RecipeBranchNameConflictException;
import com.example.mealprep.recipe.exception.RecipeBranchNameReservedException;
import com.example.mealprep.recipe.exception.RecipeBranchNotFoundException;
import com.example.mealprep.recipe.exception.RecipeBranchPointInvalidException;
import com.example.mealprep.recipe.exception.RecipeCatalogueViolationException;
import com.example.mealprep.recipe.exception.RecipeDiffCrossBranchException;
import com.example.mealprep.recipe.exception.RecipeDiffNotComputedException;
import com.example.mealprep.recipe.exception.RecipeImageNotFoundException;
import com.example.mealprep.recipe.exception.RecipeImageStorageException;
import com.example.mealprep.recipe.exception.RecipeImportFailedException;
import com.example.mealprep.recipe.exception.RecipeImportFailureException;
import com.example.mealprep.recipe.exception.RecipeImportNotFoundException;
import com.example.mealprep.recipe.exception.RecipeNotFoundException;
import com.example.mealprep.recipe.exception.RecipeSubstitutionNotFoundException;
import com.example.mealprep.recipe.exception.RecipeVersionConflictException;
import com.example.mealprep.recipe.exception.RecipeVersionNotFoundException;
import com.example.mealprep.recipe.exception.SubstitutionOriginalNotInVersionException;
import com.example.mealprep.recipe.exception.SubstitutionPromotionPreconditionException;
import com.example.mealprep.recipe.exception.SubstitutionRecordPreconditionException;
import com.example.mealprep.recipe.exception.SubstitutionTerminalStateException;
import com.example.mealprep.recipe.exception.UnsupportedRecipeImageMimeException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Recipe-specific exception → {@link ProblemDetail} mapper. Annotated {@link
 * Order#HIGHEST_PRECEDENCE} so it fires before {@code GlobalExceptionHandler}'s
 * {@code @ExceptionHandler(Exception.class)} catch-all (which would otherwise swallow these into
 * 500s).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RecipeExceptionHandler {

  @ExceptionHandler(RecipeNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleRecipeNotFound(
      RecipeNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "recipe-not-found",
            "Recipe not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeImportFailureException.class)
  public ResponseEntity<ProblemDetail> handleRecipeImportFailure(
      RecipeImportFailureException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "recipe-import-failure",
            "Recipe import failed",
            req.getRequestURI());
    pd.setProperty("failureReason", ex.failureReason());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeImportFailedException.class)
  public ResponseEntity<ProblemDetail> handleRecipeImportFailed(
      RecipeImportFailedException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.INTERNAL_SERVER_ERROR,
            ex.getMessage(),
            "recipe-import-failed",
            "Imported recipe could not be persisted",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeImportNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleRecipeImportNotFound(
      RecipeImportNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "recipe-import-not-found",
            "Recipe import provenance not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeVersionNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleRecipeVersionNotFound(
      RecipeVersionNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "recipe-version-not-found",
            "Recipe version not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeCatalogueViolationException.class)
  public ResponseEntity<ProblemDetail> handleRecipeCatalogueViolation(
      RecipeCatalogueViolationException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "recipe-catalogue-violation",
            "Recipe catalogue violation",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(NoChangesException.class)
  public ResponseEntity<ProblemDetail> handleNoChanges(
      NoChangesException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_REQUEST,
            ex.getMessage(),
            "no-changes",
            "No changes",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeDiffNotComputedException.class)
  public ResponseEntity<ProblemDetail> handleRecipeDiffNotComputed(
      RecipeDiffNotComputedException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "recipe-diff-not-computed",
            "Recipe diff not computed",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeDiffCrossBranchException.class)
  public ResponseEntity<ProblemDetail> handleRecipeDiffCrossBranch(
      RecipeDiffCrossBranchException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "recipe-diff-cross-branch",
            "Recipe diff cross branch",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeBranchNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleRecipeBranchNotFound(
      RecipeBranchNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "recipe-branch-not-found",
            "Recipe branch not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeBranchPointInvalidException.class)
  public ResponseEntity<ProblemDetail> handleRecipeBranchPointInvalid(
      RecipeBranchPointInvalidException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "recipe-branch-point-invalid",
            "Recipe branch-point version invalid",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeBranchNameConflictException.class)
  public ResponseEntity<ProblemDetail> handleRecipeBranchNameConflict(
      RecipeBranchNameConflictException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "recipe-branch-name-conflict",
            "Recipe branch name conflict",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeBranchNameReservedException.class)
  public ResponseEntity<ProblemDetail> handleRecipeBranchNameReserved(
      RecipeBranchNameReservedException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "recipe-branch-name-reserved",
            "Recipe branch name reserved",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeVersionConflictException.class)
  public ResponseEntity<ProblemDetail> handleRecipeVersionConflict(
      RecipeVersionConflictException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "recipe-version-conflict",
            "Recipe version conflict",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeSubstitutionNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleRecipeSubstitutionNotFound(
      RecipeSubstitutionNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "recipe-substitution-not-found",
            "Recipe substitution not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(SubstitutionOriginalNotInVersionException.class)
  public ResponseEntity<ProblemDetail> handleSubstitutionOriginalNotInVersion(
      SubstitutionOriginalNotInVersionException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "substitution-original-not-in-version",
            "Substitution original ingredient missing on version",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(SubstitutionTerminalStateException.class)
  public ResponseEntity<ProblemDetail> handleSubstitutionTerminalState(
      SubstitutionTerminalStateException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "substitution-terminal-state",
            "Substitution is in a terminal state",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(SubstitutionPromotionPreconditionException.class)
  public ResponseEntity<ProblemDetail> handleSubstitutionPromotionPrecondition(
      SubstitutionPromotionPreconditionException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "substitution-promotion-precondition",
            "Substitution must be ACCEPTED to promote",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(SubstitutionRecordPreconditionException.class)
  public ResponseEntity<ProblemDetail> handleSubstitutionRecordPrecondition(
      SubstitutionRecordPreconditionException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "substitution-record-precondition",
            "Substitution must be ACCEPTED to record plan application",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  // ---------------- recipe-02a image storage ----------------

  @ExceptionHandler(RecipeAccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleRecipeAccessDenied(
      RecipeAccessDeniedException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.FORBIDDEN,
            ex.getMessage(),
            "recipe-access-denied",
            "Recipe access denied",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(UnsupportedRecipeImageMimeException.class)
  public ResponseEntity<ProblemDetail> handleUnsupportedRecipeImageMime(
      UnsupportedRecipeImageMimeException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            ex.getMessage(),
            "recipe-image-unsupported-mime",
            "Unsupported recipe image MIME type",
            req.getRequestURI());
    pd.setProperty("detectedMime", ex.detectedMime());
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeImageStorageException.class)
  public ResponseEntity<ProblemDetail> handleRecipeImageStorage(
      RecipeImageStorageException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.INTERNAL_SERVER_ERROR,
            ex.getMessage(),
            "recipe-image-storage-failure",
            "Recipe image storage failure",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RecipeImageNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleRecipeImageNotFound(
      RecipeImageNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "recipe-image-not-found",
            "Recipe image not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * Map Spring's {@link MaxUploadSizeExceededException} (raised by the multipart resolver when an
   * upload exceeds {@code spring.servlet.multipart.max-file-size}) to HTTP 413. Without this, the
   * exception falls through to {@code GlobalExceptionHandler.handleUnexpected} and surfaces as a
   * 500 — the ticket spec requires 413. Mapping lives here (recipe handler) rather than the
   * cross-cutting {@code GlobalExceptionHandler} because image upload is the only multipart entry
   * point in the codebase; if a second one lands, promote this to the global handler.
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ProblemDetail> handleMaxUploadSizeExceeded(
      MaxUploadSizeExceededException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "Uploaded file exceeds the configured maximum size.",
            "recipe-image-too-large",
            "Recipe image too large",
            req.getRequestURI());
    pd.setProperty("maxUploadSizeBytes", ex.getMaxUploadSize());
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
