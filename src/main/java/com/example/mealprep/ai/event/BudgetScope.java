package com.example.mealprep.ai.event;

/**
 * The cost-budget scope a {@link CostBudgetExceededEvent} was raised for (lld/ai.md §Events).
 *
 * <ul>
 *   <li>{@link #DAILY_USER} — a single user's rolling-daily spend cap. Soft by default
 *       (alert-and-proceed); the event carries {@code userId}.
 *   <li>{@link #MONTHLY_TOTAL} — the system-wide rolling-monthly spend cap. Hard by default
 *       (block); the event carries a {@code null} {@code userId} because it bills the system, not a
 *       person.
 * </ul>
 */
public enum BudgetScope {
  DAILY_USER,
  MONTHLY_TOTAL
}
