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
 * below the configured confidence floor are placed in {@link CandidateFilterOutcome#rejected()};
 * the runner emits one {@code AI_FILTER_REJECTED} scrape row per rejection (discovery-4).
 *
 * <p><strong>Skip-and-flag on outage (discovery-3 / LLD line 584).</strong> If the AI dispatcher
 * throws or returns null for a given candidate (transient outage, parse failure), the candidate is
 * <em>kept</em> (passed through) and a WARN is logged — an AI outage must never silently shrink the
 * candidate set. Only a genuine model decision produces a rejection; the deterministic
 * hard-constraint filter downstream remains the safety net.
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
  public CandidateFilterOutcome filter(
      List<DiscoveryCandidate> candidates, DiscoveryConstraints constraints, UUID userId) {
    if (candidates == null || candidates.isEmpty()) {
      return CandidateFilterOutcome.keepAll(candidates == null ? List.of() : candidates);
    }
    BigDecimal floor = properties.candidateFilterMinConfidence();
    List<DiscoveryCandidate> kept = new ArrayList<>(candidates.size());
    List<CandidateFilterOutcome.Rejection> rejected = new ArrayList<>();
    for (DiscoveryCandidate candidate : candidates) {
      CandidateFilterTask task = new CandidateFilterTask(candidate, constraints, userId, null);
      try {
        CandidateFilterResult result = aiService.execute(task);
        if (result == null) {
          // Skip-and-flag: a null dispatch result is an AI fault, not a model rejection — keep it.
          log.warn(
              "AI filter returned null for candidate {} — keeping (skip-and-flag)",
              candidate.candidateUrl());
          kept.add(candidate);
          continue;
        }
        if (!result.relevant()) {
          log.debug(
              "AI filter rejected candidate {} (confidence={}, reason={})",
              candidate.candidateUrl(),
              result.confidence(),
              result.reason());
          rejected.add(
              new CandidateFilterOutcome.Rejection(
                  candidate, reasonText("not relevant", result.confidence(), result.reason())));
          continue;
        }
        BigDecimal confidence = result.confidence() == null ? BigDecimal.ZERO : result.confidence();
        if (confidence.compareTo(floor) < 0) {
          log.debug(
              "AI filter low-confidence drop for candidate {} (confidence={} < floor={})",
              candidate.candidateUrl(),
              confidence,
              floor);
          rejected.add(
              new CandidateFilterOutcome.Rejection(
                  candidate,
                  reasonText(
                      "below confidence floor " + floor, result.confidence(), result.reason())));
          continue;
        }
        kept.add(candidate);
      } catch (RuntimeException ex) {
        // Skip-and-flag per discovery-3 / LLD line 584: an AI dispatch failure must NOT silently
        // drop the candidate. Keep it (flagged via WARN); the hard-constraint filter still guards
        // downstream.
        log.warn(
            "AI filter call threw for candidate {} ({}): {} — keeping (skip-and-flag)",
            candidate.candidateUrl(),
            ex.getClass().getSimpleName(),
            ex.getMessage());
        kept.add(candidate);
      }
    }
    return new CandidateFilterOutcome(kept, rejected);
  }

  private static String reasonText(String base, BigDecimal confidence, String modelReason) {
    StringBuilder sb = new StringBuilder(base);
    if (confidence != null) {
      sb.append(" (confidence=").append(confidence).append(')');
    }
    if (modelReason != null && !modelReason.isBlank()) {
      sb.append(": ").append(modelReason);
    }
    return sb.toString();
  }
}
