package com.example.mealprep.ops.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Operational status snapshot returned by {@code GET /api/v1/admin/status} (capability C-G-032).
 * Aggregates coarse health/observability signals across modules for an operator dashboard.
 *
 * @param status overall verdict — {@code "UP"} when the database is reachable, {@code "DEGRADED"}
 *     otherwise (the process is serving but a critical dependency is down)
 * @param checkedAt when this snapshot was taken (UTC)
 * @param dbConnected true when a validation query against the primary datasource succeeded
 * @param lastAiCallAt timestamp of the most recent AI provider call attempt, or null if none logged
 * @param lastUsdaCallAt timestamp of the most recent USDA call this process made, or null if none
 *     since startup (in-memory, single-instance signal — resets on restart)
 * @param aiMonthToDatePence system-wide AI spend in pence from the start of the current calendar
 *     month (UTC) to {@code checkedAt}
 */
public record AdminStatusDto(
    String status,
    Instant checkedAt,
    boolean dbConnected,
    Instant lastAiCallAt,
    Instant lastUsdaCallAt,
    BigDecimal aiMonthToDatePence) {}
