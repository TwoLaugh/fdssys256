package com.example.mealprep.provisions.exception;

import java.util.UUID;

/**
 * Thrown when no budget row exists yet for the calling user. Mapped to HTTP 404 — the calling
 * surface is per-user; missing means "not yet initialised", a follow-up PUT bootstraps the row.
 */
public class BudgetNotFoundException extends ProvisionsException {

  private final UUID userId;

  public BudgetNotFoundException(UUID userId) {
    super("Budget not found for user " + userId);
    this.userId = userId;
  }

  public UUID userId() {
    return userId;
  }
}
