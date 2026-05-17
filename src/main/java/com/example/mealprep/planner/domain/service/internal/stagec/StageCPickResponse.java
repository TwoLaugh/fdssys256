package com.example.mealprep.planner.domain.service.internal.stagec;

/**
 * Structured-output payload the LLM returns for Stage C (prompt #8). Jackson deserialises the
 * model's {@code tool_use} input into this record — component names match the JSON keys verbatim so
 * no {@code @JsonProperty} annotations are needed. Per {@code lld/planner.md} line 856.
 *
 * @param chosenIndex 0..N-1 — the picked candidate's position in the score-sorted list
 * @param reasoning free-text rationale; surfaced verbatim in the decision log (01l)
 */
public record StageCPickResponse(int chosenIndex, String reasoning) {}
