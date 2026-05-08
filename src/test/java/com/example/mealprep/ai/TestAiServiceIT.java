package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testdata.AiTestData;
import com.example.mealprep.ai.testing.TestAiService;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test verifying that {@link TestAiService} is the active dispatcher in the test
 * profile and that no audit row records a real Anthropic model id (i.e. no HTTP call ever leaves
 * the JVM in this profile).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class TestAiServiceIT {

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
  void aiServiceBean_isTestAiService() {
    assertThat(aiService).isInstanceOf(TestAiService.class);
  }

  @Test
  void execute_returnsCannedResponse_andRecordsTestModelId() {
    TestAiService stub = (TestAiService) aiService;
    stub.register(TaskType.FEEDBACK_CLASSIFICATION, "canned-text");

    AiTask<String> task =
        AiTestData.task(String.class)
            .ofType(TaskType.FEEDBACK_CLASSIFICATION)
            .withUserId(UUID.randomUUID())
            .build();
    String result = aiService.execute(task);
    assertThat(result).isEqualTo("canned-text");

    List<AiCallLog> rows = repository.findAll();
    assertThat(rows).hasSize(1);
    AiCallLog row = rows.get(0);
    assertThat(row.getStatus()).isEqualTo(CallStatus.SUCCEEDED);
    assertThat(row.getModelId()).startsWith(TestAiService.TEST_MODEL_ID_PREFIX);
    assertThat(row.getModelId()).doesNotStartWith("claude-");
  }

  @Test
  void recordedCalls_capturesEachDispatchInOrder() {
    TestAiService stub = (TestAiService) aiService;
    stub.register(TaskType.FEEDBACK_CLASSIFICATION, "first");
    stub.register(TaskType.INGREDIENT_MAPPING, "second");

    aiService.execute(
        AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build());
    aiService.execute(AiTestData.task(String.class).ofType(TaskType.INGREDIENT_MAPPING).build());

    List<TestAiService.RecordedCall> calls = stub.recordedCalls();
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0).taskType()).isEqualTo(TaskType.FEEDBACK_CLASSIFICATION);
    assertThat(calls.get(1).taskType()).isEqualTo(TaskType.INGREDIENT_MAPPING);
  }

  @Test
  void noAuditRowEverRecordsRealClaudeModelId() {
    // Defensive — even after registering a few tasks and dispatching, every persisted row must
    // carry the synthetic test-stub prefix. This is the explicit "no HTTP escape" guard.
    TestAiService stub = (TestAiService) aiService;
    stub.register(TaskType.RECIPE_ADAPTATION, "x");
    stub.register(TaskType.PLANNER_STAGE_C, "y");
    aiService.execute(AiTestData.task(String.class).ofType(TaskType.RECIPE_ADAPTATION).build());
    aiService.execute(AiTestData.task(String.class).ofType(TaskType.PLANNER_STAGE_C).build());

    List<AiCallLog> rows = repository.findAll();
    assertThat(rows)
        .as(
            "Every row in test mode must carry the test-stub prefix; a 'claude-' id implies HTTP escape.")
        .allSatisfy(row -> assertThat(row.getModelId()).doesNotStartWith("claude-"));
  }
}
