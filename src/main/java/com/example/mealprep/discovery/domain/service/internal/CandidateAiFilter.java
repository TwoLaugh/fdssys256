package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import java.util.List;
import java.util.UUID;

/**
 * Internal helper interface for cheap candidate triage between {@code DiscoverySource.search} and
 * {@code DiscoverySource.fetchRecipe}. The live implementation is {@link AiCandidateAiFilter},
 * wired unconditionally by {@link AiCandidateAiFilterConfiguration}: it asks the AI dispatcher
 * whether each candidate is a relevant recipe to add to the catalogue and partitions the input into
 * kept vs model-rejected candidates.
 *
 * <p><strong>Failure contract (skip-and-flag).</strong> An AI-dispatch failure (transient outage,
 * parse error) must NOT silently shrink the candidate set: the candidate is kept (passed through)
 * and flagged, per LLD §Failure Modes line 584. Only a genuine model decision (relevant=false or
 * below the configured confidence floor) produces a rejection.
 *
 * <p>Per LLD lines 411-421. Lives in {@code domain.service.internal} so only the runner (also
 * package-internal in 01d) can inject it; cross-module callers don't see this interface.
 */
interface CandidateAiFilter {

  /**
   * Triage {@code candidates}, returning the kept set plus the model-rejected set so the runner can
   * emit one {@code AI_FILTER_REJECTED} scrape row per rejection (discovery-4). Candidates whose AI
   * dispatch fails are passed through into {@code kept} (skip-and-flag), never rejected.
   */
  CandidateFilterOutcome filter(
      List<DiscoveryCandidate> candidates, DiscoveryConstraints constraints, UUID userId);
}
