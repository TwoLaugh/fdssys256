package com.example.mealprep.preference.domain.service.internal;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.domain.service.internal.TasteProfileEmbeddingInputBuilder.LoadedInput;
import com.example.mealprep.preference.event.TasteProfileChangedEvent;
import com.example.mealprep.preference.spi.internal.TasteProfileEmbeddingTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Async listener that (re)computes the taste embedding after a {@link TasteProfileChangedEvent}
 * commits (preference-5 / lld/preference.md Flow 3 step 10). Mirrors {@code
 * recipe.domain.service.internal.RecipeEmbeddingListener}: {@code AFTER_COMMIT} → load + compose
 * the embedding input → {@code AiService.embed} → {@code
 * TasteProfileUpdateService.storeTasteVector}; terminal failures park the row at {@code
 * taste_vector_status = FAILED} (best-effort).
 *
 * <p><b>Resilience.</b> An embedding-provider outage must NOT brick the taste-profile update: the
 * write transaction has already committed (this runs AFTER_COMMIT, on a fresh {@code @Async} thread
 * with no inherited transaction), so a failed embed only flips the status to FAILED — the document,
 * version history, and event are untouched. The next document change re-flags PENDING and retries.
 * The planner falls back to a neutral preference sub-score when no vector is available.
 *
 * <p><b>Coalescing rapid bursts.</b> Each change publishes its event with the post-write {@code
 * documentVersion}. The embed input is composed from the document as it stands when this listener
 * runs, and {@code storeTasteVector} writes the result conditionally on the profile still being at
 * that {@code documentVersion}. If several delta-applies land in quick succession, the in-flight
 * embeds for the older versions no-op at store time (their docVersion no longer matches) and only
 * the latest version's embed sticks — a freshness guard that subsumes an explicit debounce window
 * without holding state.
 *
 * <p>No {@code @Transactional} on the listener method itself — the {@code @Async} thread starts
 * with no transaction, and the ArchUnit AFTER_COMMIT-propagation rule engages only when
 * {@code @Transactional} is present on the same method. The two DB touches each open their own
 * short-lived transaction ({@code TasteProfileEmbeddingInputBuilder.loadAndCompose}'s {@code
 * REQUIRES_NEW readOnly} load; the {@code storeTasteVector} write tx on the impl), neither held
 * across the OpenAI HTTP round-trip.
 */
@Component
public class TasteProfileEmbeddingListener {

  private static final Logger log = LoggerFactory.getLogger(TasteProfileEmbeddingListener.class);

  private final TasteProfileEmbeddingInputBuilder inputBuilder;
  private final AiService aiService;
  private final TasteProfileUpdateService updateService;
  private final String modelId;

  public TasteProfileEmbeddingListener(
      TasteProfileEmbeddingInputBuilder inputBuilder,
      AiService aiService,
      TasteProfileUpdateService updateService,
      @Value("${mealprep.preference.embedding.model-id:openai:text-embedding-3-small}")
          String modelId) {
    this.inputBuilder = inputBuilder;
    this.aiService = aiService;
    this.updateService = updateService;
    this.modelId = modelId;
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onTasteProfileChanged(TasteProfileChangedEvent event) {
    LoadedInput loaded = inputBuilder.loadAndCompose(event.userId());
    if (loaded == null) {
      log.info(
          "taste profile vanished for userId={} at embedding time; skipping embed", event.userId());
      return;
    }
    if (loaded.inputText() == null || loaded.inputText().isBlank()) {
      // An empty taste profile (e.g. freshly initialised) has no signals to embed yet. Leave the
      // status PENDING — the first delta-apply that adds real signals will re-trigger this
      // listener.
      log.info(
          "taste profile userId={} docVersion={} has no embeddable signals yet; leaving PENDING",
          event.userId(),
          loaded.documentVersion());
      return;
    }
    int docVersion = loaded.documentVersion();
    try {
      float[] vector =
          aiService.embed(
              new TasteProfileEmbeddingTask(event.userId(), loaded.inputText(), event.traceId()));
      updateService.storeTasteVector(event.userId(), vector, modelId, docVersion);
      log.info(
          "taste embedding stored userId={} docVersion={} dim={}",
          event.userId(),
          docVersion,
          vector != null ? vector.length : 0);
    } catch (RuntimeException e) {
      log.warn(
          "taste embedding failed userId={} docVersion={} reason={}; marking failed (best-effort)",
          event.userId(),
          docVersion,
          e.getClass().getSimpleName(),
          e);
      try {
        updateService.markTasteVectorFailed(event.userId(), docVersion);
      } catch (RuntimeException ignored) {
        log.warn(
            "taste embedding failure flip also failed userId={} docVersion={}",
            event.userId(),
            docVersion,
            ignored);
      }
    }
  }
}
