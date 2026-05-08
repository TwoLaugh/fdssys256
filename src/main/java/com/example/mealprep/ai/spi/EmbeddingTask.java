package com.example.mealprep.ai.spi;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module SPI for embedding requests. Sibling of {@link AiTask} — same shape, different
 * payload (text in, {@code float[]} out). The dispatcher ({@code AiService.embed}) reads the
 * methods on this interface to pick a model, audit the call, and route the call through the cache.
 *
 * <p>Calling modules implement this interface (typically as a {@code record}) and pass an instance
 * to {@link com.example.mealprep.ai.domain.service.AiService#embed}.
 */
public interface EmbeddingTask {

  /** Stable identifier so the call log distinguishes embedding sources. */
  EmbeddingTaskType type();

  /** Text to embed. The caller is responsible for whatever pre-processing they need. */
  String inputText();

  /** Optional userId — present on user-scoped embeddings (taste profile, journal entries). */
  Optional<UUID> userId();

  /** Optional traceId for decision-log correlation. */
  Optional<UUID> traceId();
}
