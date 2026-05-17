package com.example.mealprep.planner.api.dto;

import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import java.util.UUID;

/**
 * Pairs a Stage-A candidate's stable index (its position in the score-sorted candidate list) with
 * its ephemeral {@code candidateId} and the per-day nutrition rollup the LLM reasons over.
 *
 * <p>Introduced by planner-01g (LLD divergence — the LLD passed a bare {@code
 * List<CandidatePlanRollupDto>} to {@code StageCPickTask}; Stage C also needs the index so the LLM
 * can pick by ordinal and the {@code candidateId} so the composer can re-map the chosen plan). The
 * list of these goes into the prompt's {@code candidates} context key.
 *
 * <p>{@link CandidatePlanRollupDto} is consumed cross-module from {@code nutrition.api.dto}
 * (shipped by nutrition-01g) — the shape is identical and cross-module {@code api.dto} consumption
 * is allowed per the style guide.
 */
public record IndexedCandidateRollup(int index, UUID candidateId, CandidatePlanRollupDto rollup) {}
