package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.discovery.config.DiscoveryProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real {@link CandidateAiFilter} implementation that asks the AI dispatcher whether each candidate
 * is a relevant recipe to add to the catalogue. Per ticket discovery-01g §6-§10.
 *
 * <p>Per-candidate dispatch — one cheap-tier AI call per candidate. The dispatcher is responsible
 * for batching/caching/cost tracking. Candidates the model rejects (relevant=false) or returns
 * below the configured confidence floor are dropped from the returned list; the runner's downstream
 * scrape-row writer is responsible for emitting the {@code AI_FILTER_REJECTED} log row.
 *
 * <p>Defensive: if the AI dispatcher throws for a given candidate (transient outage, parse
 * failure), the candidate is dropped and a WARN is logged — the per-job aggregate skip behaviour
 * stays the same as the prior {@code Noop}-fallback path in {@code DiscoveryJobRunner}, just at the
 * per-candidate granularity.
 */
class AiCandidateAiFilter implements CandidateAiFilter {

  private static final Logger log = LoggerFactory.getLogger(AiCandidateAiFilter.class);

  private final AiService aiService;
  private final DiscoveryProperties properties;

  AiCandidateAiFilter(AiService aiService, DiscoveryProperties properties) {
    this.aiService = aiService;
    this.properties = properties;
  }

  @Override
  public List<DiscoveryCandidate> filter(
      List<DiscoveryCandidate> candidates, DiscoveryConstraints constraints, UUID userId) {
    if (candidates == null || candidates.isEmpty()) {
      return candidates;
    }
    BigDecimal floor = properties.candidateFilterMinConfidence();
    List<DiscoveryCandidate> kept = new ArrayList<>(candidates.size());
    for (DiscoveryCandidate candidate : candidates) {
      CandidateFilterTask task = new CandidateFilterTask(candidate, constraints, userId, null);
      try {
        CandidateFilterResult result = aiService.execute(task);
        if (result == null) {
          log.warn("AI filter returned null for candidate {} — dropping", candidate.candidateUrl());
          continue;
        }
        if (!result.relevant()) {
          log.debug(
              "AI filter rejected candidate {} (confidence={}, reason={})",
              candidate.candidateUrl(),
              result.confidence(),
              result.reason());
          continue;
        }
        BigDecimal confidence = result.confidence() == null ? BigDecimal.ZERO : result.confidence();
        if (confidence.compareTo(floor) < 0) {
          log.debug(
              "AI filter low-confidence drop for candidate {} (confidence={} < floor={})",
              candidate.candidateUrl(),
              confidence,
              floor);
          continue;
        }
        kept.add(candidate);
      } catch (RuntimeException ex) {
        log.warn(
            "AI filter call threw for candidate {} ({}): {} — dropping",
            candidate.candidateUrl(),
            ex.getClass().getSimpleName(),
            ex.getMessage());
      }
    }
    return kept;
  }
}
