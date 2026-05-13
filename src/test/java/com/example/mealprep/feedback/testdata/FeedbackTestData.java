package com.example.mealprep.feedback.testdata;

import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackRequest;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.CorrectionReplayStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.MisclassificationCorrection;
import com.example.mealprep.feedback.domain.entity.RoutingDecision;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.spi.Destination;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Builders for the four feedback entities. Used by 01a's ITs + mapper tests, and re-used by 01b-01g
 * once the service-layer tests start landing.
 */
public final class FeedbackTestData {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private FeedbackTestData() {}

  public static UiContextDocument uiContextDocument(UUID recipeId) {
    return new UiContextDocument(Screen.RECIPE_DETAIL, recipeId, 1, null, null, null);
  }

  public static FeedbackEntry feedbackEntry(UUID userId, String text) {
    return FeedbackEntry.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .text(text)
        .uiContext(uiContextDocument(UUID.randomUUID()))
        .submissionStatus(SubmissionStatus.RECEIVED)
        .classificationAttempts(0)
        .traceId(UUID.randomUUID())
        .build();
  }

  public static JsonNode samplePayload() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("recipeId", UUID.randomUUID().toString());
    node.put("affectsPlan", true);
    return node;
  }

  public static JsonNode sampleDestinationResult() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("ackId", UUID.randomUUID().toString());
    node.put("immediateApply", false);
    return node;
  }

  public static JsonNode sampleClarifierOptions() {
    return OBJECT_MAPPER
        .createArrayNode()
        .add(
            JsonNodeFactory.instance
                .objectNode()
                .put("destination", "RECIPE")
                .put("snippet", "make the lasagne lighter")
                .put("classifierJustification", "recipe-modification phrasing"))
        .add(
            JsonNodeFactory.instance
                .objectNode()
                .put("destination", "PREFERENCE")
                .put("snippet", "no more cream sauces")
                .put("classifierJustification", "standing preference update"));
  }

  public static RoutingLogEntry routingLogEntry(FeedbackEntry parent) {
    return RoutingLogEntry.builder()
        .id(UUID.randomUUID())
        .feedbackEntry(parent)
        .destination(Destination.RECIPE)
        .confidence(new BigDecimal("0.850"))
        .extractedFeedback("the salt was too much")
        .structuredPayload(samplePayload())
        .routingDecision(RoutingDecision.AUTO_ROUTED)
        .status(RoutingStatus.APPLIED)
        .actionTaken("queued recipe adaptation")
        .destinationResultJson(sampleDestinationResult())
        .classificationAttempt(1)
        .routedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
        .build();
  }

  public static ClarificationQuery clarificationQuery(FeedbackEntry parent) {
    return ClarificationQuery.builder()
        .id(UUID.randomUUID())
        .feedbackEntry(parent)
        .classifierOptionsJson(sampleClarifierOptions())
        .questionText("Did you mean the recipe or the standing preference?")
        .status(ClarificationStatus.PENDING)
        .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS))
        .build();
  }

  public static UiContextDto uiContextDto(UUID recipeId) {
    return new UiContextDto(Screen.RECIPE_DETAIL, recipeId, 1, null, null, null);
  }

  public static UiContextDto uiContextDtoGeneral() {
    return new UiContextDto(Screen.GENERAL, null, null, null, null, null);
  }

  public static SubmitFeedbackRequest submitFeedbackRequest(String text) {
    return new SubmitFeedbackRequest(text, uiContextDto(UUID.randomUUID()));
  }

  public static SubmitFeedbackRequest submitFeedbackRequest(String text, UiContextDto context) {
    return new SubmitFeedbackRequest(text, context);
  }

  public static MisclassificationCorrection misclassificationCorrection(
      FeedbackEntry parent, UUID originalRoutingId, UUID actorUserId) {
    return MisclassificationCorrection.builder()
        .id(UUID.randomUUID())
        .feedbackEntry(parent)
        .originalRoutingId(originalRoutingId)
        .correctedDestination(Destination.PREFERENCE)
        .userCorrectionNote("this was a standing pref, not a one-off recipe note")
        .actorUserId(actorUserId)
        .originalConfidence(new BigDecimal("0.620"))
        .originalDestination(Destination.RECIPE)
        .replayStatus(CorrectionReplayStatus.PENDING_REPLAY)
        .occurredAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
        .build();
  }
}
