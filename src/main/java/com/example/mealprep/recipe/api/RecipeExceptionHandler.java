package com.example.mealprep.recipe.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.recipe.exception.NoChangesException;
import com.example.mealprep.recipe.exception.RecipeBranchNameConflictException;
import com.example.mealprep.recipe.exception.RecipeBranchNameReservedException;
import com.example.mealprep.recipe.exception.RecipeBranchNotFoundException;
import com.example.mealprep.recipe.exception.RecipeBranchPointInvalidException;
import com.example.mealprep.recipe.exception.RecipeCatalogueViolationException;
import com.example.mealprep.recipe.exception.RecipeDiffCrossBranchException;
import com.example.mealprep.recipe.exception.RecipeDiffNotComputedException;
import com.example.mealprep.recipe.exception.RecipeImportFailureException;
import com.example.mealprep.recipe.exception.RecipeImportNotFoundException;
import com.example.mealprep.recipe.exception.RecipeNotFoundException;
import com.example.mealprep.recipe.exception.RecipeVersionNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
