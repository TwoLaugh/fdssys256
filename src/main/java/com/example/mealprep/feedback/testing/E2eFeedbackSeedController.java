package com.example.mealprep.feedback.testing;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingDecision;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.spi.Destination;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * E2E-only HTTP control plane for seeding a single PREFERENCE-routed feedback entry for the
 * authenticated user.
 *
 * <p><b>Why this exists.</b> The taste-profile AI delta pipeline ({@code
 * TasteProfileDeltaOrchestrator}) only calls the AI and bumps {@code documentVersion} when there is
 * a non-empty batch of PREFERENCE-routed feedback to process: with an empty batch the orchestrator
 * short-circuits to {@code SKIPPED_EMPTY_BATCH} — no AI call, no version bump. So the flagship "AI
 * delta update lands a learned preference" proof (and any rollback that needs a second version)
 * cannot be driven over HTTP by seeding only the taste profile and a canned AI response: the MANUAL
 * {@code refresh-now} would skip. The real product reaches this state by submitting feedback that
 * the classifier routes to PREFERENCE — an async, AI-classified path that the black-box E2E suite
 * would have to drive through a second AI stub plus routing-completion polling, far outside a
 * preference fixture's scope. This seeder instead persists the SAME real {@code FeedbackEntry} +
 * PREFERENCE {@code RoutingLogEntry} rows that {@code PreferenceDeltaPipelineIT} seeds in-process,
 * so the delta run that follows {@code refresh-now} exercises the GENUINE gather → AI(stub) →
 * applyDeltas → version-bump pipeline end-to-end.
 *
 * <p><b>Access seam.</b> Persists via the module's own {@code FeedbackEntryRepository} / {@code
 * RoutingLogRepository} (public Spring-Data beans) — the exact rows the batch gatherer reads. No
 * widening of any service API; this is fixture state, not a product write path.
 *
 * <p><b>Strictly {@code e2e}-profile-gated</b> (mirrors {@code E2eAiStubController} / {@code
 * E2ePreferenceSeedController}): the bean and its {@code /test-support/feedback/**} mappings do not
 * exist under {@code prod}/{@code dev}/{@code test} (unmapped 404 in prod). The current user is
 * resolved server-side via {@link CurrentUserResolver}; the seeder never accepts a {@code userId}
 * param, so a scenario can only seed its own user's feedback.
 */
@RestController
@RequestMapping("/test-support/feedback")
@Profile("e2e")
@Tag(name = "E2E Test Support")
public class E2eFeedbackSeedController {

  /** Confidence ≥ 0.8 → AUTO_ROUTED (matches the IT's realistic high-confidence routing). */
  private static final BigDecimal AUTO_ROUTED_CONFIDENCE = new BigDecimal("0.920");

  private final FeedbackEntryRepository feedbackEntryRepository;
  private final RoutingLogRepository routingLogRepository;
  private final CurrentUserResolver currentUserResolver;
  private final Clock clock;

  public E2eFeedbackSeedController(
      FeedbackEntryRepository feedbackEntryRepository,
      RoutingLogRepository routingLogRepository,
      CurrentUserResolver currentUserResolver,
      Clock clock) {
    this.feedbackEntryRepository = feedbackEntryRepository;
    this.routingLogRepository = routingLogRepository;
    this.currentUserResolver = currentUserResolver;
    this.clock = clock;
  }

  /**
   * Seed one PREFERENCE-routed feedback entry (a realistic positive ingredient statement) for the
   * calling user, so the next taste-profile delta run has a non-empty batch to process. Returns the
   * persisted feedback + routing ids.
   *
   * @return 201 with {@code {feedbackId, routingId}}
   */
  @PostMapping(path = "/preference-routed/seed", produces = MediaType.APPLICATION_JSON_VALUE)
  @Transactional
  public org.springframework.http.ResponseEntity<Map<String, UUID>> seedPreferenceRoutedFeedback() {
    UUID userId = requireCurrentUserId();
    Instant now = Instant.now(clock);

    UUID feedbackId = UUID.randomUUID();
    String text = "I really love prawns in a quick high-heat stir fry";
    FeedbackEntry entry =
        FeedbackEntry.builder()
            .id(feedbackId)
            .userId(userId)
            .traceId(UUID.randomUUID())
            .text(text)
            .uiContext(new UiContextDocument(Screen.GENERAL, null, null, null, null, null))
            .submissionStatus(SubmissionStatus.ROUTED)
            .classificationAttempts(1)
            .lastClassifiedAt(now)
            .routingLog(new ArrayList<>())
            .build();
    FeedbackEntry savedEntry = feedbackEntryRepository.save(entry);

    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("note", "preference");
    UUID routingId = UUID.randomUUID();
    RoutingLogEntry log =
        RoutingLogEntry.builder()
            .id(routingId)
            .feedbackEntry(savedEntry)
            .destination(Destination.PREFERENCE)
            .confidence(AUTO_ROUTED_CONFIDENCE)
            .extractedFeedback(text)
            .structuredPayload(payload)
            .routingDecision(RoutingDecision.AUTO_ROUTED)
            .status(RoutingStatus.APPLIED)
            .classificationAttempt(1)
            .routedAt(now)
            .build();
    routingLogRepository.save(log);

    return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("feedbackId", feedbackId, "routingId", routingId));
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
