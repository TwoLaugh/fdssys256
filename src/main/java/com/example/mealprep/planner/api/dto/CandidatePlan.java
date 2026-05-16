package com.example.mealprep.planner.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Final candidate produced by the Stage-A beam search — complete (one {@link SlotAssignment} per
 * slot in the week, in week-order) with a populated {@link ScoreResult}. The composer (01j) selects
 * the top {@code 1} for the active plan and may surface the remainder as alternatives.
 *
 * <p>{@code candidateId} is ephemeral — generated per search run, not persisted. The downstream
 * persistence step (01j) re-maps assignments onto the durable {@code MealSlot} / {@code
 * ScheduledRecipe} entities.
 */
public record CandidatePlan(
    UUID candidateId,
    LocalDate weekStartDate,
    List<SlotAssignment> assignments,
    ScoreResult scoreResult) {}
