package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.EmbeddingTask;
import com.example.mealprep.recipe.domain.service.internal.RecipeEmbeddingInputBuilder;
import com.example.mealprep.recipe.domain.service.internal.RecipeEmbeddingListener;
import com.example.mealprep.recipe.event.RecipeVersionCreatedEvent;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.spi.internal.RecipeEmbeddingTask;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-level coverage for {@link RecipeEmbeddingListener}. Verifies the happy path (input compose →
 * embed → storeEmbedding), the version-vanished early-exit, and the failure path that flips to
 * {@code markEmbeddingFailed}. End-to-end async wiring is exercised by the existing
 * spring-boot-test ITs.
 */
@ExtendWith(MockitoExtension.class)
class RecipeEmbeddingListenerTest {

  private static final String MODEL_ID = "openai:text-embedding-3-small";

  @Mock private RecipeEmbeddingInputBuilder inputBuilder;
  @Mock private AiService aiService;
  @Mock private RecipeWriteApi writeApi;

  private RecipeEmbeddingListener listener() {
    return new RecipeEmbeddingListener(inputBuilder, aiService, writeApi, MODEL_ID);
  }

  @Test
  void happyPath_composes_embeds_stores() {
    UUID versionId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    when(inputBuilder.loadAndCompose(versionId)).thenReturn("lemon chicken");
    float[] vector = {0.1f, 0.2f};
    when(aiService.embed(any(EmbeddingTask.class))).thenReturn(vector);

    listener()
        .onRecipeVersionCreated(
            new RecipeVersionCreatedEvent(
                versionId, UUID.randomUUID(), UUID.randomUUID(), 1, traceId, Instant.now()));

    ArgumentCaptor<EmbeddingTask> captor = ArgumentCaptor.forClass(EmbeddingTask.class);
    verify(aiService).embed(captor.capture());
    EmbeddingTask task = captor.getValue();
    assertThat(task).isInstanceOf(RecipeEmbeddingTask.class);
    assertThat(((RecipeEmbeddingTask) task).versionId()).isEqualTo(versionId);
    assertThat(task.inputText()).isEqualTo("lemon chicken");
    assertThat(task.traceId()).hasValue(traceId);

    verify(writeApi).storeEmbedding(eq(versionId), eq(vector), eq(MODEL_ID));
    verify(writeApi, never()).markEmbeddingFailed(any());
  }

  @Test
  void versionVanished_earlyExit_noWriteApiCall() {
    UUID versionId = UUID.randomUUID();
    when(inputBuilder.loadAndCompose(versionId)).thenReturn(null);

    listener()
        .onRecipeVersionCreated(
            new RecipeVersionCreatedEvent(
                versionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                Instant.now()));

    verify(aiService, never()).embed(any());
    verify(writeApi, never()).storeEmbedding(any(), any(), anyString());
    verify(writeApi, never()).markEmbeddingFailed(any());
  }

  @Test
  void embedFailure_marksFailed() {
    UUID versionId = UUID.randomUUID();
    when(inputBuilder.loadAndCompose(versionId)).thenReturn("lemon chicken");
    when(aiService.embed(any(EmbeddingTask.class)))
        .thenThrow(new AiUnavailableException("openai 5xx", null));

    listener()
        .onRecipeVersionCreated(
            new RecipeVersionCreatedEvent(
                versionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                Instant.now()));

    verify(writeApi, never()).storeEmbedding(any(), any(), anyString());
    verify(writeApi).markEmbeddingFailed(versionId);
  }
}
