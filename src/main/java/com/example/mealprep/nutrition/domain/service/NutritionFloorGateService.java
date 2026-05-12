package com.example.mealprep.nutrition.domain.service;

import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.api.dto.FloorGateResultDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic floor-gate facade consumed by the planner's multiplicative scoring kill-switch.
 * Verbatim from LLD §NutritionFloorGateService.
 *
 * <p>Pure read + compute — loads the caller's {@code NutritionTargets} aggregate, walks the
 * candidate per-day rollup, and returns the list of breached hard floors plus a pass/fail bit. No
 * persistence; no events.
 *
 * <p>In-process only — the planner injects this interface directly. 01g additionally exposes a
 * side-door REST endpoint {@code POST /api/v1/nutrition/floor-gate/evaluate} for operator and
 * integration-test access.
 */
public interface NutritionFloorGateService {

  /**
   * Evaluate the hard-floor gate for {@code userId} against {@code rollup}. Returns {@code
   * passed=true} when the user has no targets configured, or when every hard floor (macro {@code
   * floorG != null} or any explicitly hard-floored micro) is met across every day of the rollup.
   */
  FloorGateResultDto evaluate(UUID userId, CandidatePlanRollupDto rollup);

  /**
   * Evaluate the gate for every {@code userId} against the same {@code rollup}. Returns a {@code
   * LinkedHashMap} preserving input order. Duplicate {@code userId}s collapse via last-write-wins.
   *
   * <p>Per LLD divergence: this iterates {@link #evaluate} per user (no bulk repo call) — a
   * household maxes at ~6 members so the per-user loop is more readable than zipping a bulk fetch.
   */
  Map<UUID, FloorGateResultDto> evaluateForHousehold(
      List<UUID> userIds, CandidatePlanRollupDto rollup);
}
