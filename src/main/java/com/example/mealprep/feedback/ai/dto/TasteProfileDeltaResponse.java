package com.example.mealprep.feedback.ai.dto;

import java.util.List;

/**
 * Structured-output payload returned by {@code PreferenceTasteProfileDeltaTask} (preference-01g)
 * per {@code lld/prompts/01-taste-profile-delta.md:57-62}.
 *
 * <ul>
 *   <li>{@code deltas} — 0..50 proposed operations (empty when feedback warrants no change).
 *   <li>{@code overallReasoning} — per-batch summary for the decision log / version snapshot.
 *   <li>{@code warnings} — per-call notes the user might want to know (e.g. hard-constraint signal
 *       detected, >3 archive ops, classifier-misroute suspicion).
 * </ul>
 */
public record TasteProfileDeltaResponse(
    List<AiTasteProfileDelta> deltas, String overallReasoning, List<String> warnings) {

  /** Null-safe deltas — Jackson may bind {@code null} for an omitted array. */
  public List<AiTasteProfileDelta> deltas() {
    return deltas == null ? List.of() : deltas;
  }

  /** Null-safe warnings. */
  public List<String> warnings() {
    return warnings == null ? List.of() : warnings;
  }
}
