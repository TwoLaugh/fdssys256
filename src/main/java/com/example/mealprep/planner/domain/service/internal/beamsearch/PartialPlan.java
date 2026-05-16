package com.example.mealprep.planner.domain.service.internal.beamsearch;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.ScoreBreakdownDocument;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In-flight beam entry — a possibly-incomplete plan plus the running composite score. The beam
 * grows one {@link SlotAssignment} at a time as the search advances slot-by-slot; {@link
 * BeamPruner} retains the top {@code width} by {@link #currentScore()} after every slot.
 *
 * <p>Package-private — {@link CandidatePlan} is the public final shape consumed outside the search.
 */
record PartialPlan(
    LocalDate weekStartDate, List<SlotAssignment> assignments, BigDecimal currentScore) {

  PartialPlan {
    // Defensive copy so callers can't mutate the assignments list out from under the beam.
    assignments = List.copyOf(assignments);
  }

  static PartialPlan empty(LocalDate weekStartDate) {
    return new PartialPlan(weekStartDate, List.of(), BigDecimal.ZERO);
  }

  /** Append an assignment without changing the score (used for pinned slots). */
  PartialPlan append(SlotAssignment assignment) {
    List<SlotAssignment> next = new ArrayList<>(assignments.size() + 1);
    next.addAll(assignments);
    next.add(assignment);
    return new PartialPlan(weekStartDate, next, currentScore);
  }

  /** Return a copy with the given composite score. */
  PartialPlan withScore(BigDecimal newScore) {
    return new PartialPlan(weekStartDate, assignments, newScore);
  }

  /** Materialise the partial plan as a {@link CandidatePlan} view for scoring callbacks. */
  CandidatePlan toCandidatePlanView(UUID candidateId, ScoreResult result) {
    return new CandidatePlan(candidateId, weekStartDate, assignments, result);
  }

  /** Build a placeholder {@link CandidatePlan} for the scoring callback (no result yet). */
  CandidatePlan toCandidatePlanView(UUID candidateId) {
    ScoreBreakdownDocument empty =
        new ScoreBreakdownDocument(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            true,
            true,
            "v1-uniform");
    return new CandidatePlan(
        candidateId, weekStartDate, assignments, new ScoreResult(currentScore, empty));
  }
}
