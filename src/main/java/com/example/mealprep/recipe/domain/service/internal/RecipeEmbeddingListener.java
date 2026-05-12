package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.recipe.event.RecipeVersionCreatedEvent;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.spi.internal.RecipeEmbeddingTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Async listener that runs the OpenAI embedding pipeline after a new {@code RecipeVersion} commits.
 * Per LLD line 132: {@code AFTER_COMMIT} → load and compose embedding input → {@code
 * AiService.embed} → {@code RecipeWriteApi.storeEmbedding}; terminal failures park the row at
 * {@code embedding_status = 'failed'}.
 *
 * <p>Round-7 propagation rule does NOT apply: the listener method has NO {@code @Transactional}
 * annotation (the {@code @Async} thread starts on a fresh thread with no inherited transaction).
 * The rule "must be REQUIRES_NEW or NOT_SUPPORTED" fires only when {@code @Transactional} IS
 * present alongside {@code @TransactionalEventListener} on the SAME method; absent
 * {@code @Transactional}, the rule does not engage.
 *
 * <p>Transaction shape (two short-lived txes, neither held across the OpenAI HTTP round-trip):
 *
 * <ol>
 *   <li>{@link RecipeEmbeddingInputBuilder#loadAndCompose} — own {@code REQUIRES_NEW readOnly} tx
 *       to materialise the input string; closes before the embed call.
 *   <li>The {@code aiService.embed} call — no tx.
 *   <li>{@link RecipeWriteApi#storeEmbedding} (or {@link RecipeWriteApi#markEmbeddingFailed}) —
 *       opens its own {@code REQUIRES} write tx (declared on the impl).
 * </ol>
 */
@Component
public class RecipeEmbeddingListener {

  private static final Logger log = LoggerFactory.getLogger(RecipeEmbeddingListener.class);

  private final RecipeEmbeddingInputBuilder inputBuilder;
  private final AiService aiService;
  private final RecipeWriteApi writeApi;
  private final String modelId;

  public RecipeEmbeddingListener(
      RecipeEmbeddingInputBuilder inputBuilder,
      AiService aiService,
      RecipeWriteApi writeApi,
      @Value("${mealprep.recipe.embedding.model-id:openai:text-embedding-3-small}")
          String modelId) {
    this.inputBuilder = inputBuilder;
    this.aiService = aiService;
    this.writeApi = writeApi;
    this.modelId = modelId;
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onRecipeVersionCreated(RecipeVersionCreatedEvent event) {
    String inputText = inputBuilder.loadAndCompose(event.versionId());
    if (inputText == null || inputText.isBlank()) {
      log.info(
          "recipe version {} not found (or empty input) at embedding time; skipping",
          event.versionId());
      return;
    }
    try {
      float[] vector =
          aiService.embed(new RecipeEmbeddingTask(event.versionId(), inputText, event.traceId()));
      writeApi.storeEmbedding(event.versionId(), vector, modelId);
      log.info(
          "recipe embedding stored versionId={} dim={}",
          event.versionId(),
          vector != null ? vector.length : 0);
    } catch (RuntimeException e) {
      log.warn(
          "recipe embedding failed versionId={} reason={}; marking failed",
          event.versionId(),
          e.getClass().getSimpleName(),
          e);
      try {
        writeApi.markEmbeddingFailed(event.versionId());
      } catch (RuntimeException ignored) {
        log.warn(
            "recipe embedding failure flip also failed versionId={}", event.versionId(), ignored);
      }
    }
  }
}
