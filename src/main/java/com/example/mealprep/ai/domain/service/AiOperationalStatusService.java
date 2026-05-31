package com.example.mealprep.ai.domain.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Cross-module read-side SPI exposing AI-module operational signals for the system status endpoint
 * ({@code GET /api/v1/admin/status}, capability C-G-032). Distinct from {@link
 * AiCostTrackingService} (per-user windowed spend for budgets / per-user dashboards) and {@link
 * AdminAiQueryService} (paged call-log admin views): this surface answers the two coarse questions
 * the ops status aggregator needs — "when did we last call the AI provider?" and "how much have we
 * spent this calendar month system-wide?".
 *
 * <p>All amounts are in pence (micropence ÷ 1_000_000, HALF_UP to two decimals), consistent with
 * {@link AiCostTrackingService}.
 */
public interface AiOperationalStatusService {

  /**
   * Timestamp of the most recent {@code ai_call_log} row (any status) — i.e. the last time the
   * system attempted an AI provider call. Empty when no calls have been logged yet.
   */
  Optional<Instant> lastAiCallAt();

  /**
   * System-wide spend (pence) on SUCCEEDED calls from the start of the current calendar month (UTC)
   * to now. Zero when there has been no spend this month.
   */
  BigDecimal monthToDatePence();
}
