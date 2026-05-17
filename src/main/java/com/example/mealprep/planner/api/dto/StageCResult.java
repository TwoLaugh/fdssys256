package com.example.mealprep.planner.api.dto;

import com.example.mealprep.planner.domain.entity.AugmentationSource;

/**
 * Outcome of Stage C (LLM pick-of-N). Consumed by the composer (planner-01j) to select the active
 * candidate and to set the plan's {@code aiAugmented} flag.
 *
 * <p><b>LLD divergence.</b> {@code lld/planner.md} line 828 locks a 3-field shape {@code
 * StageCResult(int chosenIndex, String reasoning, AugmentationSource source)}. planner-01g adds a
 * 4th field, {@code fallback}, so the composer can wire the per-plan {@code aiAugmented = false}
 * flag explicitly rather than string-matching the {@code reasoning} text. The {@code source} is
 * <b>always {@link AugmentationSource#LLM}</b> — it records "Stage C selection origin" semantically
 * (the user's intent at Stage C was to invoke the LLM); there is no {@code DETERMINISTIC} enum
 * value and adding one was rejected because the per-plan {@code aiAugmented} boolean already tracks
 * the deterministic-fallback case. {@code fallback} disambiguates within {@code LLM}.
 *
 * @param chosenIndex 0-based index into the score-sorted candidate list
 * @param reasoning free-text rationale (LLM-supplied on success; a fixed string on fallback);
 *     recorded in the decision log by 01l
 * @param source always {@link AugmentationSource#LLM} (see class Javadoc)
 * @param fallback {@code true} when the deterministic top-scored candidate was selected because the
 *     LLM was unavailable, failed, or returned an out-of-range index
 */
public record StageCResult(
    int chosenIndex, String reasoning, AugmentationSource source, boolean fallback) {}
