package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AI dispatcher task carrying a single discovery candidate + the job's constraints, asking the
 * model whether the candidate is a relevant recipe to add to the catalogue. Per ticket
 * discovery-01g §8.
 *
 * <p>Cheap-tier (Haiku) — candidate filtering is high-volume, low-stakes-per-call.
 */
public final class CandidateFilterTask implements AiTask<CandidateFilterResult> {

  public static final String PROMPT_NAME = "discovery/candidate-filter";
  public static final int PROMPT_VERSION = 1;

  private final DiscoveryCandidate candidate;
  private final DiscoveryConstraints constraints;
  private final UUID userId;
  private final UUID traceId;

  public CandidateFilterTask(
      DiscoveryCandidate candidate, DiscoveryConstraints constraints, UUID userId, UUID traceId) {
    if (candidate == null) {
      throw new IllegalArgumentException("candidate must not be null");
    }
    if (constraints == null) {
      throw new IllegalArgumentException("constraints must not be null");
    }
    this.candidate = candidate;
    this.constraints = constraints;
    this.userId = userId;
    this.traceId = traceId;
  }

  @Override
  public TaskType type() {
    return TaskType.DISCOVERY_FILTERING;
  }

  @Override
  public ModelTier tier() {
    return ModelTier.CHEAP;
  }

  @Override
  public PromptRef prompt() {
    return new PromptRef(PROMPT_NAME, PROMPT_VERSION);
  }

  @Override
  public Class<CandidateFilterResult> outputType() {
    return CandidateFilterResult.class;
  }

  @Override
  public Map<String, Object> variables() {
    return Map.of(
        "candidate.canonicalUrl", nullToBlank(candidate.candidateUrl()),
        "candidate.title", nullToBlank(candidate.snippetTitle()),
        "candidate.snippet", nullToBlank(candidate.snippetDescription()),
        "constraints.cuisines", listOrEmpty(constraints.requiredCuisines()),
        "constraints.dietaryFlags", listOrEmpty(constraints.dietaryFlags()),
        "constraints.maxPrepMins",
            constraints.maxTotalTimeMins() == null ? "n/a" : constraints.maxTotalTimeMins());
  }

  @Override
  public Optional<List<ToolDefinition>> tools() {
    return Optional.empty();
  }

  @Override
  public Optional<UUID> userId() {
    return Optional.ofNullable(userId);
  }

  @Override
  public Optional<UUID> traceId() {
    return Optional.ofNullable(traceId);
  }

  private static String nullToBlank(String s) {
    return s == null ? "" : s;
  }

  private static List<String> listOrEmpty(List<String> in) {
    return in == null ? List.of() : in;
  }
}
