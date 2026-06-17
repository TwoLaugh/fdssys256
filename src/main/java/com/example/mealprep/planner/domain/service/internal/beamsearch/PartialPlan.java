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
 * In-flight beam entry — a possibly-incomplete plan plus the running composite score and the
 * incremental scoring accumulators. The beam grows one {@link SlotAssignment} at a time as the
 * search advances slot-by-slot; {@link BeamPruner} retains the top {@code width} by {@link
 * #currentScore()} after every slot.
 *
 * <p>{@code incrementalState} carries the running raw accumulators behind the {@code
 * BeamCandidateScorer} seam (preference/time sums, distinct variety sets, per-day nutrition totals,
 * …) so the beam derives a child's composite by folding in ONE slot instead of re-scoring the whole
 * partial plan. It is opaque to the beam — threaded from {@code emptyState} through {@code append}
 * into {@code composite}, never inspected. {@code null} for the no-score {@link
 * #append(SlotAssignment)} path (pinned slots, which skip scoring) and on a plain {@code empty}.
 *
 * <p>Package-private — {@link CandidatePlan} is the public final shape consumed outside the search.
 */
record PartialPlan(
    LocalDate weekStartDate,
    List<SlotAssignment> assignments,
    BigDecimal currentScore,
    Object incrementalState) {

  PartialPlan {
    // Defensive copy so callers can't mutate the assignments list out from under the beam.
    assignments = List.copyOf(assignments);
  }

  static PartialPlan empty(LocalDate weekStartDate) {
    return new PartialPlan(weekStartDate, List.of(), BigDecimal.ZERO, null);
  }

  /** Empty partial seeded with the incremental accumulators (used by the scored beam path). */
  static PartialPlan empty(LocalDate weekStartDate, Object incrementalState) {
    return new PartialPlan(weekStartDate, List.of(), BigDecimal.ZERO, incrementalState);
  }

  /** Append an assignment without changing the score (used for pinned slots). */
  PartialPlan append(SlotAssignment assignment) {
    List<SlotAssignment> next = new ArrayList<>(assignments.size() + 1);
    next.addAll(assignments);
    next.add(assignment);
    return new PartialPlan(weekStartDate, next, currentScore, incrementalState);
  }

  /** Append an assignment and carry the derived incremental accumulators (scored beam path). */
  PartialPlan append(SlotAssignment assignment, Object nextState) {
    List<SlotAssignment> next = new ArrayList<>(assignments.size() + 1);
    next.addAll(assignments);
    next.add(assignment);
    return new PartialPlan(weekStartDate, next, currentScore, nextState);
  }

  /** Return a copy with the given composite score. */
  PartialPlan withScore(BigDecimal newScore) {
    return new PartialPlan(weekStartDate, assignments, newScore, incrementalState);
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
