package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.entity.CallErrorKind;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.internal.AiCallRecorder;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testdata.AiTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration test for the audit-row write path. Boots the full Spring context (Testcontainers
 * Postgres) and verifies:
 *
 * <ul>
 *   <li>{@code AiCallRecorder.recordPending} writes a row visible after commit;
 *   <li>{@code recordSuccess} updates the row in place;
 *   <li>{@code REQUIRES_NEW} survives a caller transaction rollback.
 * </ul>
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class AiCallLogIT {

  @Autowired private AiCallRecorder recorder;
  @Autowired private AiCallLogRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;

  @AfterEach
  void cleanup() {
    repository.deleteAll();
  }

  @Test
  void recordPending_thenSuccess_persistsBothWritesInOneRow() {
    AiTask<String> task =
        AiTestData.task(String.class)
            .ofType(TaskType.FEEDBACK_CLASSIFICATION)
            .withTier(ModelTier.CHEAP)
            .withUserId(UUID.randomUUID())
            .withTraceId(UUID.randomUUID())
            .build();

    UUID callId = recorder.recordPending(task, ModelTier.CHEAP, "haiku-id");
    AiCallLog pending = repository.findById(callId).orElseThrow();
    assertThat(pending.getStatus()).isEqualTo(CallStatus.PENDING);
    assertThat(pending.getCostMicroPence()).isEqualTo(0L);

    recorder.recordSuccess(callId, 100, 50, 250);
    AiCallLog completed = repository.findById(callId).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(CallStatus.SUCCEEDED);
    assertThat(completed.getRequestTokens()).isEqualTo(100);
    assertThat(completed.getResponseTokens()).isEqualTo(50);
    assertThat(completed.getLatencyMs()).isEqualTo(250);
    assertThat(completed.getCompletedAt()).isNotNull();
    assertThat(repository.count()).isEqualTo(1L);
  }

  @Test
  void recordPending_failure_then_FAILED_writesErrorKind() {
    AiTask<String> task = AiTestData.task(String.class).build();
    UUID callId = recorder.recordPending(task, ModelTier.MID, "sonnet-id");
    recorder.recordFailure(callId, CallErrorKind.AI_UNAVAILABLE, 1234);

    AiCallLog row = repository.findById(callId).orElseThrow();
    assertThat(row.getStatus()).isEqualTo(CallStatus.FAILED);
    assertThat(row.getErrorKind()).isEqualTo(CallErrorKind.AI_UNAVAILABLE);
    assertThat(row.getLatencyMs()).isEqualTo(1234);
  }

  @Test
  void recordPending_survivesCallerRollback_REQUIRES_NEW() {
    TransactionTemplate outer = new TransactionTemplate(transactionManager);
    AiTask<String> task = AiTestData.task(String.class).ofType(TaskType.INGREDIENT_MAPPING).build();

    UUID[] callIdHolder = new UUID[1];
    assertThatThrownBy(
            () ->
                outer.executeWithoutResult(
                    status -> {
                      callIdHolder[0] = recorder.recordPending(task, ModelTier.CHEAP, "haiku-id");
                      // Force the outer tx to roll back AFTER the audit row was written in
                      // its own REQUIRES_NEW transaction. The audit row must persist anyway.
                      throw new IllegalStateException("simulated caller rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(callIdHolder[0]).isNotNull();
    assertThat(repository.findById(callIdHolder[0])).isPresent();
  }

  @Test
  void promptRefVersion_persistsAsInteger() {
    AiTask<String> task = AiTestData.task(String.class).build();
    UUID callId = recorder.recordPending(task, ModelTier.MID, "sonnet-id");
    AiCallLog row = repository.findById(callId).orElseThrow();
    assertThat(row.getPromptRefName()).isEqualTo("test/echo");
    assertThat(row.getPromptRefVersion()).isEqualTo(1);
  }
}
