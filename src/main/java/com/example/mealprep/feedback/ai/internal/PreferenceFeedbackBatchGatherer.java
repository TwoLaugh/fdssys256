package com.example.mealprep.feedback.ai.internal;

import com.example.mealprep.feedback.ai.dto.ClassifiedFeedbackEvent;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.spi.Destination;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the 1-{@value #MAX_BATCH} {@link ClassifiedFeedbackEvent} batch a delta-update run feeds
 * to the AI task (preference-01g §4). Reads the user's PREFERENCE-routed {@link RoutingLogEntry}
 * rows routed strictly after a floor instant (the cursor's last run, or EPOCH when no run has
 * happened), oldest-first, capped at {@value #MAX_BATCH} (the prompt's stated batch ceiling).
 *
 * <p>{@code REQUIRES_NEW readOnly} so the read commits in its own short transaction and the
 * eager-loaded {@code feedbackEntry} is read inside the session (no lazy-load escape).
 */
@Component
public class PreferenceFeedbackBatchGatherer {

  /** Prompt's stated batch ceiling — "1-10 events the classifier routed here". */
  public static final int MAX_BATCH = 10;

  private final RoutingLogRepository routingLogRepository;

  public PreferenceFeedbackBatchGatherer(RoutingLogRepository routingLogRepository) {
    this.routingLogRepository = routingLogRepository;
  }

  /**
   * Gather the PREFERENCE-routed feedback events for {@code userId} routed after {@code since},
   * oldest-first, capped at {@value #MAX_BATCH}. {@code since == null} means "from the beginning".
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public List<ClassifiedFeedbackEvent> gather(UUID userId, Instant since) {
    Instant floor = since == null ? Instant.EPOCH : since;
    List<RoutingLogEntry> rows =
        routingLogRepository.findRoutedForUserSince(
            userId, Destination.PREFERENCE, floor, PageRequest.of(0, MAX_BATCH));
    return rows.stream().map(PreferenceFeedbackBatchGatherer::toEvent).toList();
  }

  private static ClassifiedFeedbackEvent toEvent(RoutingLogEntry row) {
    FeedbackEntry entry = row.getFeedbackEntry();
    // Prefer the classifier's extracted snippet (preference-flavoured slice) over the raw entry
    // text; fall back to the raw text when the extraction was empty.
    String userText =
        row.getExtractedFeedback() != null && !row.getExtractedFeedback().isBlank()
            ? row.getExtractedFeedback()
            : entry.getText();
    return new ClassifiedFeedbackEvent(
        entry.getId(),
        userText,
        contextSummary(entry.getUiContext()),
        row.getConfidence(),
        entry.getCreatedAt());
  }

  /** Short human-readable UI-context summary for the prompt's {@code contextSummary} field. */
  private static String contextSummary(UiContextDocument ctx) {
    if (ctx == null || ctx.screen() == null) {
      return "general feedback";
    }
    StringBuilder sb = new StringBuilder("feedback on ");
    sb.append(
        switch (ctx.screen()) {
          case RECIPE_DETAIL -> "a recipe";
          case PLAN_MEAL_DETAIL, PLAN_VIEW -> "a planned meal";
          case GROCERY -> "the shopping list";
          case NUTRITION_DASHBOARD -> "the nutrition dashboard";
          case SETTINGS -> "the settings page";
          case GENERAL -> "the app";
        });
    if (ctx.referenceDate() != null) {
      sb.append(" (").append(ctx.referenceDate()).append(")");
    }
    return sb.toString();
  }
}
