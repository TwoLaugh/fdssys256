package com.example.mealprep.household.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.household.exception.EmptyHouseholdMergeException;
import com.example.mealprep.household.exception.HouseholdInviteAlreadyAcceptedException;
import com.example.mealprep.household.exception.HouseholdInviteExpiredException;
import com.example.mealprep.household.exception.HouseholdInviteNotFoundException;
import com.example.mealprep.household.exception.HouseholdInviteRevokedException;
import com.example.mealprep.household.exception.HouseholdMemberNotFoundException;
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import com.example.mealprep.household.exception.HouseholdSettingsNotFoundException;
import com.example.mealprep.household.exception.InsufficientHouseholdRoleException;
import com.example.mealprep.household.exception.LastPrimaryRemovalException;
import com.example.mealprep.household.exception.UserAlreadyInHouseholdException;
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
 * Household-specific exception → {@link ProblemDetail} mapper. Annotated {@link
 * Order#HIGHEST_PRECEDENCE} so it fires before {@code GlobalExceptionHandler}'s
 * {@code @ExceptionHandler(Exception.class)} catch-all (which would otherwise swallow these into
 * 500s).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HouseholdExceptionHandler {

  @ExceptionHandler(HouseholdNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleHouseholdNotFound(
      HouseholdNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "household-not-found",
            "Household not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(UserAlreadyInHouseholdException.class)
  public ResponseEntity<ProblemDetail> handleUserAlreadyInHousehold(
      UserAlreadyInHouseholdException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "user-already-in-household",
            "User already in household",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(HouseholdSettingsNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleHouseholdSettingsNotFound(
      HouseholdSettingsNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "household-settings-not-found",
            "Household settings not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(InsufficientHouseholdRoleException.class)
  public ResponseEntity<ProblemDetail> handleInsufficientHouseholdRole(
      InsufficientHouseholdRoleException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.FORBIDDEN,
            ex.getMessage(),
            "insufficient-household-role",
            "Insufficient household role",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(HouseholdInviteNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleHouseholdInviteNotFound(
      HouseholdInviteNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "household-invite-not-found",
            "Household invite not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(HouseholdInviteExpiredException.class)
  public ResponseEntity<ProblemDetail> handleHouseholdInviteExpired(
      HouseholdInviteExpiredException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.GONE,
            ex.getMessage(),
            "household-invite-expired",
            "Household invite expired",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.GONE)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(HouseholdInviteRevokedException.class)
  public ResponseEntity<ProblemDetail> handleHouseholdInviteRevoked(
      HouseholdInviteRevokedException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.GONE,
            ex.getMessage(),
            "household-invite-revoked",
            "Household invite revoked",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.GONE)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(HouseholdInviteAlreadyAcceptedException.class)
  public ResponseEntity<ProblemDetail> handleHouseholdInviteAlreadyAccepted(
      HouseholdInviteAlreadyAcceptedException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "household-invite-already-accepted",
            "Household invite already accepted or revoked",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(HouseholdMemberNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleHouseholdMemberNotFound(
      HouseholdMemberNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "household-member-not-found",
            "Household member not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(LastPrimaryRemovalException.class)
  public ResponseEntity<ProblemDetail> handleLastPrimaryRemoval(
      LastPrimaryRemovalException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "last-primary-removal",
            "Cannot remove or demote the last primary while other members remain",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(EmptyHouseholdMergeException.class)
  public ResponseEntity<ProblemDetail> handleEmptyHouseholdMerge(
      EmptyHouseholdMergeException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "empty-household-merge",
            "Empty household merge",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * Map DB-level unique-constraint collisions raised by household-module writes (chiefly the
   * one-primary-per-household partial index {@code idx_household_member_one_primary} and the {@code
   * UNIQUE (user_id)} constraint on {@code household_member}) to HTTP 409. Lives here rather than
   * in {@code GlobalExceptionHandler} so the global advice stays module-agnostic — matches the
   * nutrition module's precedent (see {@code NutritionExceptionHandler}).
   *
   * <p><b>Scoped to household constraints.</b> This advice, {@code NutritionExceptionHandler} and
   * {@code PlannerExceptionHandler} each register a {@code DataIntegrityViolationException} handler
   * at {@link Order#HIGHEST_PRECEDENCE}, so whichever Spring orders first would otherwise claim
   * EVERY {@code DataIntegrityViolationException} app-wide — which is how a nutrition micro-target
   * / per-meal 23505 was being mislabelled as a "household integrity violation". We therefore only
   * emit the household-specific response when the violated constraint is household-owned (its name
   * mentions {@code household}); any other constraint gets a module-neutral {@code
   * data-integrity-violation} 409 instead of a misleading household label.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
      DataIntegrityViolationException ex, HttpServletRequest req) {
    if (!isHouseholdConstraint(ex)) {
      ProblemDetail pd =
          ProblemDetailSupport.build(
              HttpStatus.CONFLICT,
              "A database integrity constraint was violated.",
              "data-integrity-violation",
              "Data integrity violation",
              req.getRequestURI());
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(pd);
    }
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            "Household integrity constraint violated.",
            "household-integrity-violation",
            "Household integrity violation",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * Whether {@code ex}'s underlying constraint violation is household-owned. Prefers the constraint
   * name reported by Hibernate's {@link org.hibernate.exception.ConstraintViolationException} (a
   * definitive non-household name returns {@code false} immediately); falls back to scanning the
   * exception-chain messages for the {@code household} token when no named constraint is present
   * (e.g. a {@code DataIntegrityViolationException} constructed only from a SQL message).
   */
  private static boolean isHouseholdConstraint(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
        String name = cve.getConstraintName();
        if (name != null) {
          return name.toLowerCase(java.util.Locale.ROOT).contains("household");
        }
      }
    }
    for (Throwable t = ex; t != null; t = t.getCause()) {
      String msg = t.getMessage();
      if (msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("household")) {
        return true;
      }
    }
    return false;
  }
}
