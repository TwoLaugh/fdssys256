package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import java.util.List;

/**
 * Result of {@link CandidateAiFilter#filter}: the candidates the AI gate kept and the ones it
 * rejected (with a human-readable reason). The runner forwards {@code kept} to the fetch phase and
 * writes one {@code AI_FILTER_REJECTED} scrape row per {@link Rejection} so the audit log records
 * why a candidate vanished between {@code candidatesSeen} and {@code candidatesAfterFilter}
 * (discovery-4).
 *
 * <p>Candidates whose AI dispatch fails (outage / parse error) are placed in {@code kept}, not
 * {@code rejected}, to honour the skip-and-flag failure contract (discovery-3 / LLD line 584).
 */
record CandidateFilterOutcome(List<DiscoveryCandidate> kept, List<Rejection> rejected) {

  /** A candidate the AI gate rejected, with the reason recorded on the scrape row. */
  record Rejection(DiscoveryCandidate candidate, String reason) {}

  /** Pass-through outcome: every candidate kept, none rejected (e.g. empty input). */
  static CandidateFilterOutcome keepAll(List<DiscoveryCandidate> candidates) {
    return new CandidateFilterOutcome(candidates, List.of());
  }
}
