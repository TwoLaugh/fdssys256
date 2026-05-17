package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.CorrectionReplayStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingDecision;
import com.example.mealprep.feedback.domain.entity.RoutingFailureKind;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.service.internal.ConfidenceGate;
import com.example.mealprep.feedback.domain.service.internal.CorrectionReplayer;
import com.example.mealprep.feedback.domain.service.internal.FeedbackRouter;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link CorrectionReplayer}: synthetic shape + replay-status mapping. */
@ExtendWith(MockitoExtension.class)
class CorrectionReplayerTest {

  @Mock private FeedbackRouter router;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private CorrectionReplayer replayer() {
    return new CorrectionReplayer(router, objectMapper);
  }

  @Test
  void buildSynthetic_setsConfidenceOne_fullText_autoRouted_payloadFromUiContext() {
    UUID recipeId = UUID.randomUUID();
    UUID mealSlotId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "full feedback text");
    entry.setUiContext(
        new UiContextDocument(Screen.PLAN_MEAL_DETAIL, recipeId, 2, mealSlotId, planId, null));

    ConfidenceGate.ScoredClassification scored =
        replayer()
            .buildSynthetic(
                entry, FeedbackTestData.correctionRequest(Destination.PROVISIONS, "note"));

    assertThat(scored.classification().destination()).isEqualTo(Destination.PROVISIONS);
    assertThat(scored.classification().confidence()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(scored.classification().extractedFeedback()).isEqualTo("full feedback text");
    assertThat(scored.decision()).isEqualTo(RoutingDecision.AUTO_ROUTED);
    var payload = scored.classification().structuredPayload();
    assertThat(payload.get("recipeId").asText()).isEqualTo(recipeId.toString());
    assertThat(payload.get("mealSlotId").asText()).isEqualTo(mealSlotId.toString());
    assertThat(payload.get("planId").asText()).isEqualTo(planId.toString());
  }

  @Test
  void buildSynthetic_omitsNullUiContextFields() {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "t");
    entry.setUiContext(new UiContextDocument(Screen.GENERAL, null, null, null, null, null));

    ConfidenceGate.ScoredClassification scored =
        replayer()
            .buildSynthetic(
                entry, FeedbackTestData.correctionRequest(Destination.PREFERENCE, null));

    assertThat(scored.classification().structuredPayload().isEmpty()).isTrue();
  }

  @Test
  void replay_delegatesToRouter() {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "t");
    ConfidenceGate.ScoredClassification scored =
        replayer()
            .buildSynthetic(entry, FeedbackTestData.correctionRequest(Destination.NUTRITION, null));
    FeedbackRouter.RouteReplayResult expected =
        new FeedbackRouter.RouteReplayResult(UUID.randomUUID(), RoutingStatus.APPLIED, null);
    when(router.routeOneForReplay(eq(entry.getId()), any())).thenReturn(expected);

    assertThat(replayer().replay(entry, scored)).isEqualTo(expected);
  }

  @Test
  void mapReplayStatus_appliedAndAwaiting_mapToApplied() {
    CorrectionReplayer r = replayer();
    assertThat(r.mapReplayStatus(RoutingStatus.APPLIED, null))
        .isEqualTo(CorrectionReplayStatus.APPLIED);
    assertThat(r.mapReplayStatus(RoutingStatus.AWAITING_USER_APPROVAL, null))
        .isEqualTo(CorrectionReplayStatus.APPLIED);
  }

  @Test
  void mapReplayStatus_destinationValidationOrBusiness_mapToRejected() {
    CorrectionReplayer r = replayer();
    assertThat(r.mapReplayStatus(RoutingStatus.FAILED, RoutingFailureKind.DESTINATION_VALIDATION))
        .isEqualTo(CorrectionReplayStatus.DESTINATION_REJECTED);
    assertThat(r.mapReplayStatus(RoutingStatus.FAILED, RoutingFailureKind.DESTINATION_BUSINESS))
        .isEqualTo(CorrectionReplayStatus.DESTINATION_REJECTED);
  }

  @Test
  void mapReplayStatus_transientAiUnknown_mapToFailed() {
    CorrectionReplayer r = replayer();
    assertThat(r.mapReplayStatus(RoutingStatus.FAILED, RoutingFailureKind.TRANSIENT))
        .isEqualTo(CorrectionReplayStatus.FAILED);
    assertThat(r.mapReplayStatus(RoutingStatus.FAILED, RoutingFailureKind.AI_UNAVAILABLE))
        .isEqualTo(CorrectionReplayStatus.FAILED);
    assertThat(r.mapReplayStatus(RoutingStatus.FAILED, RoutingFailureKind.UNKNOWN))
        .isEqualTo(CorrectionReplayStatus.FAILED);
  }
}
