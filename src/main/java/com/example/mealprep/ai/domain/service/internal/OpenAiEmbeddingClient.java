package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.openai.client.OpenAIClient;
import com.openai.errors.BadRequestException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnprocessableEntityException;
import com.openai.models.CreateEmbeddingResponse;
import com.openai.models.Embedding;
import com.openai.models.EmbeddingCreateParams;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Adapter over the {@code openai-java} SDK for the {@code /v1/embeddings} endpoint. Same retry
 * shape as {@link AnthropicClient}: 3 attempts, exponential backoff (200 / 400 / 800 ms). 4xx
 * errors (bad request, auth, not found) surface immediately as {@link AiInvalidRequestException};
 * other transient SDK exceptions retry up to {@link AiProperties#maxRetries()} attempts before
 * surfacing as {@link AiUnavailableException}.
 *
 * <p>The {@link OpenAIClient} bean is optional at construction so the app can boot in test mode
 * without an OpenAI key — production code paths that hit {@link #embed} will fail fast with a clear
 * error if the bean is absent.
 */
@Component
public class OpenAiEmbeddingClient {

  private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);

  static final long INITIAL_BACKOFF_MS = 200L;

  private final ObjectProvider<OpenAIClient> clientProvider;
  private final AiProperties properties;
  private Sleeper sleeper;

  public OpenAiEmbeddingClient(
      ObjectProvider<OpenAIClient> clientProvider, AiProperties properties) {
    this.clientProvider = clientProvider;
    this.properties = properties;
    this.sleeper = Thread::sleep;
  }

  /** Test seam — replaces the production {@code Thread::sleep} so retries don't block tests. */
  public void setSleeper(Sleeper sleeper) {
    this.sleeper = sleeper;
  }

  /**
   * Make a synchronous embedding request. Returns the raw {@code float[]} vector along with the
   * input-token usage reported by OpenAI.
   *
   * @throws AiInvalidRequestException 4xx response (caller error — bad input, no auth, etc.)
   * @throws AiUnavailableException retries exhausted on transient failures, or no client bean
   *     configured
   * @throws AiInvalidResponseException response shape was malformed (no embedding data returned)
   */
  public EmbeddingResult embed(String inputText, String model) {
    OpenAIClient client = clientProvider.getIfAvailable();
    if (client == null) {
      throw new AiUnavailableException(
          "OpenAIClient bean is not configured (mealprep.ai.openai-api-key missing)");
    }

    EmbeddingCreateParams params =
        EmbeddingCreateParams.builder()
            .input(inputText)
            .model(model)
            .encodingFormat(EmbeddingCreateParams.EncodingFormat.FLOAT)
            .build();

    int maxAttempts = Math.max(1, properties.maxRetries());
    RuntimeException lastTransient = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        CreateEmbeddingResponse response = client.embeddings().create(params);
        return parse(response);
      } catch (BadRequestException
          | UnauthorizedException
          | PermissionDeniedException
          | NotFoundException
          | UnprocessableEntityException fatal) {
        throw new AiInvalidRequestException("OpenAI 4xx: " + safeMessage(fatal), fatal);
      } catch (OpenAIException transientEx) {
        lastTransient = transientEx;
        if (attempt == maxAttempts) {
          break;
        }
        long delay = INITIAL_BACKOFF_MS << (attempt - 1);
        log.warn(
            "openai embedding call failed (attempt {}/{}), retrying after {}ms: {}",
            attempt,
            maxAttempts,
            delay,
            transientEx.getMessage());
        sleepQuietly(delay);
      }
    }
    throw new AiUnavailableException(
        "OpenAI embedding call failed after " + maxAttempts + " attempts", lastTransient);
  }

  private EmbeddingResult parse(CreateEmbeddingResponse response) {
    List<Embedding> data = response.data();
    if (data == null || data.isEmpty()) {
      throw new AiInvalidResponseException("OpenAI embedding response had no data array");
    }
    List<Double> raw = data.get(0).embedding();
    if (raw == null || raw.isEmpty()) {
      throw new AiInvalidResponseException("OpenAI embedding response had empty vector");
    }
    float[] vector = new float[raw.size()];
    for (int i = 0; i < raw.size(); i++) {
      Double v = raw.get(i);
      vector[i] = v == null ? 0f : v.floatValue();
    }
    Integer inputTokens = null;
    if (response.usage() != null) {
      long t = response.usage().promptTokens();
      inputTokens = (int) Math.min(t, Integer.MAX_VALUE);
    }
    return new EmbeddingResult(vector, inputTokens);
  }

  private static String safeMessage(OpenAIException ex) {
    String m = ex.getMessage();
    if (m == null) {
      return ex.getClass().getSimpleName();
    }
    return m.length() <= 256 ? m : m.substring(0, 256) + "...";
  }

  private void sleepQuietly(long ms) {
    try {
      sleeper.sleep(ms);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AiUnavailableException("Interrupted during retry backoff", ex);
    }
  }

  /** Test seam — production wires {@link Thread#sleep(long)}. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(long ms) throws InterruptedException;
  }

  /** Result of a successful embedding call: the vector + token usage for cost tracking. */
  public record EmbeddingResult(float[] vector, Integer inputTokens) {}
}
