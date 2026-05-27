package com.example.mealprep.grocery.domain.entity;

import java.time.Instant;

/**
 * One append-only diagnostic record in {@link GroceryOrder}'s {@code automationFailureLog} JSONB
 * array. Per lld/grocery.md line 186 / 378. Read whole, never filtered on inner fields — a textbook
 * fit for the JSONB rule.
 */
public record AutomationFailureRecord(String step, String message, Instant occurredAt) {}
