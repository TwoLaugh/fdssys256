package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.discovery.api.dto.DiscoveryJobDto;
import com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest;
import com.example.mealprep.discovery.api.mapper.DiscoveryJobMapper;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.event.DiscoveryJobStartedEvent;
import com.example.mealprep.discovery.exception.DiscoveryConstraintInvalidException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a new {@link DiscoveryJob} in the {@code QUEUED} state and publishes {@link
 * DiscoveryJobStartedEvent} AFTER_COMMIT so the async runner can pick it up.
 *
 * <p>Lives on a separate bean from {@code DiscoveryServiceImpl} so callers (notably {@code
 * runJobSync}) reach it through Spring's proxy by construction — the {@code @Transactional} advice
 * and the AFTER_COMMIT event semantics fire because the call crosses a bean boundary, not because
 * of a lazy self-reference.
 */
@Component
public class DiscoveryJobStarter {

  private final DiscoveryJobRepository jobRepository;
  private final DiscoverySourceRepository sourceRepository;
  private final DiscoveryJobMapper jobMapper;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;

  public DiscoveryJobStarter(
      DiscoveryJobRepository jobRepository,
      DiscoverySourceRepository sourceRepository,
      DiscoveryJobMapper jobMapper,
      ApplicationEventPublisher eventPublisher,
      ObjectMapper objectMapper) {
    this.jobRepository = jobRepository;
    this.sourceRepository = sourceRepository;
    this.jobMapper = jobMapper;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
  }

  /**
   * Persists the QUEUED job under the supplied id and publishes {@link DiscoveryJobStartedEvent}
   * AFTER_COMMIT. {@code runJobSync} pre-generates the id so it can register its sync waiter before
   * the runner could possibly observe the event (register-before-publish race fix).
   */
  @Transactional
  public DiscoveryJobDto startJobWithId(UUID userId, StartDiscoveryJobRequest request, UUID jobId) {
    List<DiscoverySource> resolved = resolveSources(request.sourceKeys());
    if (resolved.isEmpty()) {
      throw new DiscoveryConstraintInvalidException(
          "zero enabled sources match the requested subset");
    }

    UUID traceId = request.traceId() != null ? request.traceId() : UUID.randomUUID();
    List<String> sourceKeys = new ArrayList<>(resolved.size());
    for (DiscoverySource s : resolved) {
      sourceKeys.add(s.getSourceKey());
    }

    DiscoveryJob job =
        DiscoveryJob.builder()
            .id(jobId)
            .userId(userId)
            .trigger(request.trigger())
            .requestedCount(request.requestedCount())
            .constraintsJson(objectMapper.valueToTree(request.constraints()))
            .sourcesRequested(sourceKeys)
            .status(DiscoveryJobStatus.QUEUED)
            .queuedAt(Instant.now())
            .candidatesSeen(0)
            .candidatesAfterFilter(0)
            .recipesIngested(0)
            .recipesSkippedDuplicate(0)
            .sourcesSucceeded(new ArrayList<>())
            .sourcesFailed(new ArrayList<>())
            .traceId(traceId)
            .build();
    // saveAndFlush: the response DTO carries optimisticVersion / queuedAt freshly bumped by
    // Hibernate, and `save()` doesn't flush — would surface stale state.
    DiscoveryJob saved = jobRepository.saveAndFlush(job);

    eventPublisher.publishEvent(
        new DiscoveryJobStartedEvent(
            saved.getId(),
            userId,
            saved.getTrigger(),
            saved.getRequestedCount(),
            List.copyOf(saved.getSourcesRequested()),
            saved.getTraceId(),
            Instant.now()));

    return jobMapper.toDto(saved);
  }

  private List<DiscoverySource> resolveSources(List<String> requestedKeys) {
    if (requestedKeys == null) {
      return sourceRepository.findByEnabledTrue();
    }
    if (requestedKeys.isEmpty()) {
      // Caller explicitly sent an empty array — treat as "match nothing" to fail fast at the
      // zero-source guard in startJobWithId.
      return Collections.emptyList();
    }
    List<DiscoverySource> found = sourceRepository.findBySourceKeyIn(requestedKeys);
    Map<String, DiscoverySource> byKey = new HashMap<>();
    for (DiscoverySource s : found) {
      byKey.put(s.getSourceKey(), s);
    }
    List<String> errors = new ArrayList<>();
    List<DiscoverySource> resolved = new ArrayList<>();
    for (String requested : requestedKeys) {
      DiscoverySource match = byKey.get(requested);
      if (match == null || !match.isEnabled()) {
        errors.add(requested);
      } else {
        resolved.add(match);
      }
    }
    if (!errors.isEmpty()) {
      throw new DiscoveryConstraintInvalidException(
          "unknown or disabled source keys: " + String.join(", ", errors), errors);
    }
    return resolved;
  }
}
