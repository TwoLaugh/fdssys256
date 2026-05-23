package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.feedback.ai.dto.AiTasteProfileDelta;
import com.example.mealprep.feedback.ai.dto.ClassifiedFeedbackEvent;
import com.example.mealprep.feedback.ai.dto.TasteProfileDeltaResponse;
import com.example.mealprep.feedback.ai.task.PreferenceDeltaToolDefinition;
import com.example.mealprep.feedback.ai.task.PreferenceTasteProfileDeltaTask;
import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PreferenceTasteProfileDeltaTask} — SPI wiring + tool schema + round-trip.
 */
class PreferenceTasteProfileDeltaTaskTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TasteProfileDocument profile =
      TasteProfileDocument.empty(LocalDate.of(2026, 5, 23));
  private final UUID userId = UUID.randomUUID();
  private final UUID traceId = UUID.randomUUID();

  @Test
  void wiring_taskTypeTierPromptAndOutputType_matchSpec() {
    PreferenceTasteProfileDeltaTask task = newTask(null);

    assertThat(task.type()).isEqualTo(TaskType.PREFERENCE_DELTA_UPDATE);
    assertThat(task.tier()).isEqualTo(ModelTier.MID);
    assertThat(task.prompt().name()).isEqualTo("preference/taste-profile-delta-user");
    assertThat(task.prompt().version()).isEqualTo(1);
    assertThat(task.outputType()).isEqualTo(TasteProfileDeltaResponse.class);
    assertThat(task.userId()).isEqualTo(Optional.of(userId));
    assertThat(task.traceId()).isEqualTo(Optional.of(traceId));
  }

  @Test
  void tools_singleToolWithExpectedNameAndSchema() {
    PreferenceTasteProfileDeltaTask task = newTask(null);

    Optional<List<ToolDefinition>> tools = task.tools();
    assertThat(tools).isPresent();
    assertThat(tools.get()).hasSize(1);
    ToolDefinition def = tools.get().get(0);
    assertThat(def.name()).isEqualTo(PreferenceDeltaToolDefinition.TOOL_NAME);
    assertThat(def.inputSchema().get("type").asText()).isEqualTo("object");
    assertThat(def.inputSchema().get("required"))
        .anySatisfy(n -> assertThat(n.asText()).isEqualTo("deltas"));
    // The delta items enumerate all eight op types.
    assertThat(def.inputSchema().at("/properties/deltas/items/properties/type/enum")).hasSize(8);
  }

  @Test
  void variables_carrySystemPromptTimeoutAndContextKeys() {
    List<ClassifiedFeedbackEvent> batch =
        List.of(
            new ClassifiedFeedbackEvent(
                UUID.randomUUID(),
                "I love prawns",
                "feedback on a recipe",
                new BigDecimal("0.92"),
                Instant.now()));
    PreferenceTasteProfileDeltaTask task =
        new PreferenceTasteProfileDeltaTask(
            profile, batch, List.of("olives_arch"), userId, traceId, null);

    assertThat(task.variables())
        .containsKeys(
            "system_prompt",
            "timeout_seconds",
            "current_taste_profile",
            "feedback_batch",
            "recent_archive_ids",
            "user_id");
    assertThat(task.variables().get("timeout_seconds"))
        .isEqualTo(PreferenceTasteProfileDeltaTask.TIMEOUT_SECONDS);
    assertThat((String) task.variables().get("system_prompt"))
        .contains("careful editor of a user's taste profile");
    assertThat(task.variables()).doesNotContainKey("corrective_hint");
  }

  @Test
  void variables_correctiveHintPresentOnlyWhenSupplied() {
    PreferenceTasteProfileDeltaTask task = newTask("re-check fieldPaths");
    assertThat(task.variables()).containsEntry("corrective_hint", "re-check fieldPaths");
  }

  @Test
  void constructor_rejectsNullProfileAndUser() {
    assertThatThrownBy(
            () ->
                new PreferenceTasteProfileDeltaTask(
                    null, List.of(), List.of(), userId, traceId, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PreferenceTasteProfileDeltaTask(
                    profile, List.of(), List.of(), null, traceId, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void responseType_roundTripsAllEightDeltaPermits() throws Exception {
    String json =
        """
        {
          "deltas": [
            {"type":"Add","fieldPath":"likes.ingredients","item":"prawns","notes":"n",
             "evidenceFeedbackId":"f1","reasoning":"r","confidence":"MEDIUM"},
            {"type":"Remove","fieldPath":"dislikes.ingredients","item":"olives",
             "evidenceFeedbackId":"f1","reasoning":"r"},
            {"type":"Update","fieldPath":"likes.ingredients","item":"chicken","newNotes":"n",
             "evidenceFeedbackId":"f1","reasoning":"r"},
            {"type":"Archive","fieldPath":"likes.ingredients","item":"coriander",
             "archiveReason":"gone off","evidenceFeedbackId":"f1","reasoning":"r"},
            {"type":"RePromote","archivedItemKey":"olives_arch","fieldPath":"likes.ingredients",
             "evidenceFeedbackId":"f1","reasoning":"r"},
            {"type":"PromoteExperiment","hypothesisId":"h1","fieldPath":"likes.ingredients",
             "item":"rocket","evidenceFeedbackId":"f1","reasoning":"r"},
            {"type":"DiscardExperiment","hypothesisId":"h2","evidenceFeedbackId":"f1","reasoning":"r"},
            {"type":"UpdateNotes","fieldPath":"flavourPreferences.notes","newNotes":"n",
             "evidenceFeedbackId":"f1","reasoning":"r"}
          ],
          "overallReasoning": "batch summary",
          "warnings": ["heads up"]
        }
        """;

    TasteProfileDeltaResponse response =
        objectMapper.readValue(json, TasteProfileDeltaResponse.class);

    assertThat(response.deltas()).hasSize(8);
    assertThat(response.deltas().get(0)).isInstanceOf(AiTasteProfileDelta.Add.class);
    assertThat(((AiTasteProfileDelta.Add) response.deltas().get(0)).confidence())
        .isEqualTo(AiTasteProfileDelta.Confidence.MEDIUM);
    assertThat(response.deltas().get(6)).isInstanceOf(AiTasteProfileDelta.DiscardExperiment.class);
    assertThat(response.deltas().get(6).fieldPath()).isEqualTo("activeExperiments");
    assertThat(response.overallReasoning()).isEqualTo("batch summary");
    assertThat(response.warnings()).containsExactly("heads up");
  }

  @Test
  void responseType_nullArrays_areNullSafe() throws Exception {
    TasteProfileDeltaResponse response =
        objectMapper.readValue("{\"overallReasoning\":\"none\"}", TasteProfileDeltaResponse.class);
    assertThat(response.deltas()).isEmpty();
    assertThat(response.warnings()).isEmpty();
  }

  private PreferenceTasteProfileDeltaTask newTask(String correctiveHint) {
    return new PreferenceTasteProfileDeltaTask(
        profile, List.of(), List.of(), userId, traceId, correctiveHint);
  }
}
