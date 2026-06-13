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
 * <p>{@code forceRegenerateIfActive} is <b>reserved and UNREAD by the v1 composer</b> (verified +
 * pinned in the contract, frontend-gaps P3 / plan page spec §8 Q6): generating against an ACTIVE
 * week always yields a parallel GENERATED generation ({@code replacesPlanId} set) that supersedes
 * the ACTIVE plan only on accept — never a 409, never an in-place mutation. The flag stays on the
 * wire so a future force path (LLD §Flow 1 step 4's feasibility override) is non-breaking.
 */
public record GeneratePlanRequest(
    @NotNull UUID householdId,
    @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate,
    boolean forceRegenerateIfActive) {}
