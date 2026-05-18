package com.example.mealprep.planner.api.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Request body for {@code POST /api/v1/plans/generate} (planner-01j). The {@code userId} is NOT
 * accepted from the client — it is resolved server-side from the auth context and the controller
 * cross-checks household membership via {@code PlannerAuth}. {@code householdId} + {@code
 * weekStartDate} pin the (household, week) the composer plans for.
 *
 * <p>{@code forceRegenerateIfActive} mirrors LLD §Flow 1 step 4 — when {@code false} and the
 * feasibility check fails the composer still produces a draft flagged {@code qualityWarning}; the
 * flag is surfaced here so a future UI can opt into a force path.
 */
public record GeneratePlanRequest(
    @NotNull UUID householdId,
    @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate,
    boolean forceRegenerateIfActive) {}
