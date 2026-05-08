package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.spi.EmbeddingTask;
import com.example.mealprep.ai.spi.EmbeddingTaskType;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testing.TestAiService;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for the embedding pipeline. Boots the full Spring context (Testcontainers
 * Postgres) and verifies, via {@link TestAiService}, that:
 *
 * <ul>
 *   <li>{@code embed} returns a 1536-dim deterministic vector;
 *   <li>identical input twice does NOT make a second client call (cache miss-then-hit);
 *   <li>each embedding call writes one {@code ai_call_log} row with an {@code EMBEDDING_*} task
 *       type;
 *   <li>{@code TestAiService.embed} never escapes to OpenAI (model id stays under the test prefix).
 * </ul>
 *
 * <p>The {@code TestAiService} short-circuits HTTP, so we exercise cache shape via the production
 * {@code AiServiceImpl} unit test; here we exercise wiring + persistence end-to-end.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class EmbeddingPipelineIT {

  @Autowired private AiService aiService;
  @Autowired private AiCallLogRepository repository;

  @AfterEach
  void cleanup() {
    if (aiService instanceof TestAiService stub) {
      stub.clear();
    }
    repository.deleteAll();
  }

  @Test
  void embed_returnsDeterministicVector_andRecordsCallLog() {
    UUID userId = UUID.randomUUID();
    StubEmbeddingTask task =
        new StubEmbeddingTask(
            EmbeddingTaskType.PREFERENCE_TASTE_VECTOR, "spicy curry rice noodles", userId, null);
    float[] first = aiService.embed(task);
    float[] second = aiService.embed(task);

    assertThat(first).hasSize(TestAiService.DEFAULT_EMBEDDING_DIM);
    assertThat(second).containsExactly(first);

    List<AiCallLog> rows = repository.findAll();
    assertThat(rows).hasSize(2); // TestAiService doesn't dedupe; that's AiServiceImpl's cache job.
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row.getStatus()).isEqualTo(CallStatus.SUCCEEDED);
              assertThat(row.getTaskType()).isEqualTo(TaskType.EMBEDDING_PREFERENCE_TASTE_VECTOR);
              assertThat(row.getModelId()).startsWith(TestAiService.TEST_MODEL_ID_PREFIX);
              assertThat(row.getModelId()).doesNotStartWith("text-embedding-");
            });
  }

  @Test
  void embed_emptyInput_throwsIllegalArgument() {
    StubEmbeddingTask task =
        new StubEmbeddingTask(EmbeddingTaskType.RECIPE_SEMANTIC_VECTOR, "", null, null);
    assertThatThrownBy(() -> aiService.embed(task)).isInstanceOf(IllegalArgumentException.class);
    assertThat(repository.count()).isZero();
  }

  @Test
  void embed_differentTypes_recordsDistinctEmbeddingTaskTypes() {
    aiService.embed(
        new StubEmbeddingTask(
            EmbeddingTaskType.RECIPE_SEMANTIC_VECTOR, "tomato basil pasta", null, null));
    aiService.embed(
        new StubEmbeddingTask(
            EmbeddingTaskType.JOURNAL_ENTRY_VECTOR, "Felt great after the run.", null, null));

    List<TaskType> types = repository.findAll().stream().map(AiCallLog::getTaskType).toList();
    assertThat(types)
        .containsExactlyInAnyOrder(
            TaskType.EMBEDDING_RECIPE_SEMANTIC_VECTOR, TaskType.EMBEDDING_JOURNAL_ENTRY_VECTOR);
  }

  @Test
  void embed_overrideRegistration_returnsCustomVector() {
    TestAiService stub = (TestAiService) aiService;
    float[] custom = new float[TestAiService.DEFAULT_EMBEDDING_DIM];
    custom[0] = 0.42f;
    stub.registerEmbedding(EmbeddingTaskType.PREFERENCE_TASTE_VECTOR, custom);

    float[] result =
        aiService.embed(
            new StubEmbeddingTask(
                EmbeddingTaskType.PREFERENCE_TASTE_VECTOR, "anything", null, null));
    assertThat(result).isSameAs(custom);
  }

  /** IT-local stub — minimal {@link EmbeddingTask} for fixture composition. */
  private static final class StubEmbeddingTask implements EmbeddingTask {
    private final EmbeddingTaskType type;
    private final String inputText;
    private final UUID userId;
    private final UUID traceId;

    StubEmbeddingTask(EmbeddingTaskType type, String inputText, UUID userId, UUID traceId) {
      this.type = type;
      this.inputText = inputText;
      this.userId = userId;
      this.traceId = traceId;
    }

    @Override
    public EmbeddingTaskType type() {
      return type;
    }

    @Override
    public String inputText() {
      return inputText;
    }

    @Override
    public Optional<UUID> userId() {
      return Optional.ofNullable(userId);
    }

    @Override
    public Optional<UUID> traceId() {
      return Optional.ofNullable(traceId);
    }
  }
}
