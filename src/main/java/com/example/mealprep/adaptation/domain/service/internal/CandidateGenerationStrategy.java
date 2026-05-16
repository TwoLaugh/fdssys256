package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import java.util.List;

/**
 * Strategy contract for a single kind of Stage-A candidate generation. Implementations are
 * stateless and called from {@link CandidateGenerator}.
 *
 * <p>Per ticket 01c §Stage A (LLD line 743) "enumerates ingredient swaps, portion adjusts, method
 * tweaks." Each strategy emits zero or more {@link AdaptationCandidateDto}s; the generator
 * concatenates outputs (de-duplication is the scoring engine's concern).
 *
 * <p>Strategies are deliberately deterministic in v1 — full LLM-guided exploration is deferred per
 * ticket. The {@code seed} param lets the caller bias the strategy with source-specific signal
 * (e.g. {@code feedbackText}, {@code directive}) but most v1 strategies ignore it and emit a small
 * fixed set.
 */
public interface CandidateGenerationStrategy {

  /** Stable name used in tests + logs. */
  String name();

  /**
   * Produce zero or more candidates for {@code job} given the loaded {@code context}. Indices on
   * the returned DTOs are local-to-strategy ({@link CandidateGenerator} renumbers globally).
   */
  List<AdaptationCandidateDto> generate(AdaptationJob job, AdaptationContext context);
}
