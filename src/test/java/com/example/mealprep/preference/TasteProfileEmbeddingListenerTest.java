package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.EmbeddingTask;
import com.example.mealprep.preference.domain.entity.ActorType;
import com.example.mealprep.preference.domain.entity.TasteProfileChangeType;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.domain.service.internal.TasteProfileEmbeddingInputBuilder;
import com.example.mealprep.preference.domain.service.internal.TasteProfileEmbeddingInputBuilder.LoadedInput;
import com.example.mealprep.preference.domain.service.internal.TasteProfileEmbeddingListener;
import com.example.mealprep.preference.event.TasteProfileChangedEvent;
import com.example.mealprep.preference.spi.internal.TasteProfileEmbeddingTask;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage for {@link TasteProfileEmbeddingListener} (preference-5). Verifies the happy path
 * (load+compose → embed → storeTasteVector with the loaded docVersion), the profile-vanished and
 * empty-signals early exits, and the failure path that flips to {@code markTasteVectorFailed}
 * (best-effort resilience — an outage must not bubble out). End-to-end async wiring is exercised by
 * the spring-boot-test ITs.
 */
@ExtendWith(MockitoExtension.class)
class TasteProfileEmbeddingListenerTest {

  private static final String MODEL_ID = "openai:text-embedding-3-small";

  @Mock private TasteProfileEmbeddingInputBuilder inputBuilder;
  @Mock private AiService aiService;
  @Mock private TasteProfileUpdateService updateService;

  private TasteProfileEmbeddingListener listener() {
    return new TasteProfileEmbeddingListener(inputBuilder, aiService, updateService, MODEL_ID);
  }

  private static TasteProfileChangedEvent event(UUID userId, UUID traceId) {
    return new TasteProfileChangedEvent(
        userId,
        UUID.randomUUID(),
        7,
        TasteProfileChangeType.AI_DELTA_APPLIED,
        ActorType.AI,
        traceId,
        Instant.now());
  }

  @Test
  void happyPath_composes_embeds_stores() {
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    when(inputBuilder.loadAndCompose(userId))
        .thenReturn(new LoadedInput(7, "flavour likes: smoky, umami"));
    float[] vector = {0.1f, 0.2f};
    when(aiService.embed(any(EmbeddingTask.class))).thenReturn(vector);

    listener().onTasteProfileChanged(event(userId, traceId));

    ArgumentCaptor<EmbeddingTask> captor = ArgumentCaptor.forClass(EmbeddingTask.class);
    verify(aiService).embed(captor.capture());
    EmbeddingTask task = captor.getValue();
    assertThat(task).isInstanceOf(TasteProfileEmbeddingTask.class);
    assertThat(((TasteProfileEmbeddingTask) task).userId()).hasValue(userId);
    assertThat(task.inputText()).isEqualTo("flavour likes: smoky, umami");
    assertThat(task.traceId()).hasValue(traceId);

    verify(updateService).storeTasteVector(eq(userId), eq(vector), eq(MODEL_ID), eq(7));
    verify(updateService, never()).markTasteVectorFailed(any(), anyInt());
  }

  @Test
  void profileVanished_earlyExit_noEmbed() {
    UUID userId = UUID.randomUUID();
    when(inputBuilder.loadAndCompose(userId)).thenReturn(null);

    listener().onTasteProfileChanged(event(userId, null));

    verify(aiService, never()).embed(any());
    verify(updateService, never()).storeTasteVector(any(), any(), anyString(), anyInt());
    verify(updateService, never()).markTasteVectorFailed(any(), anyInt());
  }

  @Test
  void emptySignals_earlyExit_leavesPending() {
    UUID userId = UUID.randomUUID();
    when(inputBuilder.loadAndCompose(userId)).thenReturn(new LoadedInput(1, "   "));

    listener().onTasteProfileChanged(event(userId, null));

    verify(aiService, never()).embed(any());
    verify(updateService, never()).storeTasteVector(any(), any(), anyString(), anyInt());
    verify(updateService, never()).markTasteVectorFailed(any(), anyInt());
  }

  @Test
  void embedFailure_marksFailed_andDoesNotThrow() {
    UUID userId = UUID.randomUUID();
    when(inputBuilder.loadAndCompose(userId))
        .thenReturn(new LoadedInput(7, "cuisine favourites: thai"));
    when(aiService.embed(any(EmbeddingTask.class)))
        .thenThrow(new AiUnavailableException("openai 5xx", null));

    // No exception bubbles out — an embedding outage must not brick the (already committed) update.
    listener().onTasteProfileChanged(event(userId, null));

    verify(updateService, never()).storeTasteVector(any(), any(), anyString(), anyInt());
    verify(updateService).markTasteVectorFailed(userId, 7);
  }
}
