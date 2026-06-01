package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.exception.AiCircuitOpenException;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiRateLimitException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ChatCompletion;
import com.openai.models.ChatCompletionCreateParams;
import com.openai.models.CompletionUsage;
import com.openai.models.ResponseFormatJsonSchema;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link ChatClient} over the {@code openai-java} SDK's chat-completions API, using <b>structured
 * outputs</b> ({@code response_format = json_schema}) so the model returns schema-valid JSON. The
 * provider sibling of {@link AnthropicClient}: same per-task-type Resilience4j circuit breaker
 * (keyed {@code ai-${taskType}}), same {@link RetryPolicy}-classified retry loop, same failure
 * taxonomy ({@link AiCircuitOpenException} / {@link AiInvalidRequestException} / {@link
 * AiRateLimitException} / {@link AiUnavailableException}), and the same OpenAI token-usage → cost
 * mapping the embedding client already uses ({@code prompt_tokens} / {@code completion_tokens}).
 *
 * <p><b>Structured output.</b> When the task carries a {@link ToolDefinition} (the codebase's
 * structured-output mechanism), its JSON Schema is sent as {@code
 * response_format.json_schema.schema} with {@code strict=true}, so OpenAI constrains the decode to
 * that schema. The returned assistant {@code content} is validated against the same schema by
 * {@link StructuredOutputParser} (the provider-agnostic validator the Anthropic path reuses) —
 * defence-in-depth, since structured outputs can still emit a {@code refusal}. When the task
 * carries no schema we fall back to free-text JSON ({@code response_format} unset) and let the
 * dispatcher deserialise.
 *
 * <p><b>Resilience approach mirrors {@link AnthropicClient}</b> — programmatic Resilience4j (not
 * AOP) for the same reasons documented there (no self-invocation trap; unit-testable via {@code
 * new}; in-repo precedent). Per attempt: {@code OpenAIServiceException} is classified by its HTTP
 * {@link OpenAIServiceException#statusCode()} through {@link RetryPolicy#classifyStatus(int)} (429
 * rate-limit / {@code insufficient_quota} → retried with the longer backoff; 401/403 + other 4xx
 * incl. 400 invalid-request / context-length → fatal, never retried; 5xx → retried). A transport
 * {@code OpenAIIoException} (no HTTP status) classifies as {@link RetryPolicy.Category#TIMEOUT} and
 * is retried, exactly like an {@link java.io.IOException} on the Anthropic path.
 *
 * <p>The {@link OpenAIClient} bean is optional at construction (mirrors {@link
 * OpenAiEmbeddingClient}) so the app boots in test mode without a real key — a production dispatch
 * with the bean absent fails fast with {@link AiUnavailableException}. In {@code test}/{@code e2e}
 * profiles {@code TestAiService} is {@code @Primary}, so this client is never exercised and no
 * network call is made.
 */
@Component
public class OpenAiChatClient implements ChatClient {

  private static final Logger log = LoggerFactory.getLogger(OpenAiChatClient.class);

  /**
   * Default {@code max_completion_tokens} — small enough to bound runaway cost (parity with the
   * Anthropic client's {@code DEFAULT_MAX_TOKENS}).
   */
  static final long DEFAULT_MAX_COMPLETION_TOKENS = 1024L;

  private final ObjectProvider<OpenAIClient> clientProvider;
  private final AiProperties properties;
  private final ObjectMapper objectMapper;
  private final StructuredOutputParser structuredOutputParser;
  private final io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry;
  private Sleeper sleeper;
  private java.util.random.RandomGenerator jitterRandom;

  public OpenAiChatClient(
      ObjectProvider<OpenAIClient> clientProvider,
      AiProperties properties,
      ObjectMapper objectMapper,
      StructuredOutputParser structuredOutputParser,
      io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry) {
    this.clientProvider = clientProvider;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.structuredOutputParser = structuredOutputParser;
    this.circuitBreakerRegistry = circuitBreakerRegistry;
    this.sleeper = Thread::sleep;
    this.jitterRandom = java.util.concurrent.ThreadLocalRandom.current();
  }

  /** Test seam — replaces the production {@code Thread::sleep} so retries don't block tests. */
  public void setSleeper(Sleeper sleeper) {
    this.sleeper = sleeper;
  }

  /**
   * Test seam — replaces the production {@link java.util.concurrent.ThreadLocalRandom} jitter
   * source with a fixed-seed generator so backoff durations are deterministic in tests.
   */
  public void setJitterRandom(java.util.random.RandomGenerator jitterRandom) {
    this.jitterRandom = jitterRandom;
  }

  /**
   * Resolve (creating on first use) the breaker for a task type, keyed {@code ai-${taskType}}. The
   * same key-space and config as {@link AnthropicClient} so a provider swap keeps breaker semantics
   * identical.
   */
  public io.github.resilience4j.circuitbreaker.CircuitBreaker breakerFor(AiTask<?> task) {
    return circuitBreakerRegistry.circuitBreaker(
        "ai-" + task.type(), AnthropicClient.circuitBreakerConfig());
  }

  @Override
  public ChatResponse chat(AiTask<?> task, String modelId) {
    io.github.resilience4j.circuitbreaker.CircuitBreaker breaker = breakerFor(task);
    if (!breaker.tryAcquirePermission()) {
      log.warn("ai circuit OPEN for {} — short-circuiting openai call", breaker.getName());
      throw new AiCircuitOpenException(
          "AI circuit open for " + breaker.getName() + "; call short-circuited");
    }
    long start = System.nanoTime();
    try {
      ChatResponse response = chatWithRetry(task, modelId);
      breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
      return response;
    } catch (AiInvalidRequestException fatal) {
      // Caller-bug 4xx (incl. AUTH) — ignored by the breaker config, but report it so permission
      // accounting balances; it does not count toward opening.
      breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, fatal);
      throw fatal;
    } catch (AiUnavailableException transientExhausted) {
      // Transient exhaustion (incl. AiRateLimitException) — counts toward opening the breaker.
      breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, transientExhausted);
      throw transientExhausted;
    } catch (RuntimeException unexpected) {
      breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, unexpected);
      throw unexpected;
    }
  }

  /**
   * Classified retry loop. Retries {@code TIMEOUT} (5xx / transport) and {@code RATE_LIMIT} (429 /
   * {@code insufficient_quota}); surfaces a fatal 4xx immediately. Each retryable category gets its
   * own backoff base via {@link RetryPolicy#backoffWithJitter}.
   */
  private ChatResponse chatWithRetry(AiTask<?> task, String modelId) {
    OpenAIClient client = clientProvider.getIfAvailable();
    if (client == null) {
      throw new AiUnavailableException(
          "OpenAIClient bean is not configured (mealprep.ai.openai-api-key missing)");
    }
    ChatCompletionCreateParams params = buildParams(task, modelId);
    JsonNode schema = schemaFor(task);
    int maxAttempts = maxAttempts();
    AiUnavailableException lastTransient = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        ChatCompletion completion = client.chat().completions().create(params);
        return parse(completion, modelId, schema, task.outputType());
      } catch (OpenAIServiceException serviceEx) {
        RetryPolicy.Category category = RetryPolicy.classifyStatus(serviceEx.statusCode());
        switch (category) {
          case RATE_LIMIT -> {
            AiRateLimitException rl =
                new AiRateLimitException(
                    "OpenAI 429 RATE_LIMIT: " + safeMessage(serviceEx), serviceEx);
            lastTransient = rl;
            if (attempt == maxAttempts) {
              break;
            }
            sleepBackoff(
                RetryPolicy.Category.RATE_LIMIT, attempt, maxAttempts, "RATE_LIMIT", serviceEx);
            continue;
          }
          case AUTH, UNKNOWN -> {
            // 401/403 auth + other 4xx (400 invalid-request, 404, 422, context-length) — fatal.
            throw new AiInvalidRequestException(
                "OpenAI "
                    + serviceEx.statusCode()
                    + " ("
                    + category
                    + "): "
                    + safeMessage(serviceEx),
                serviceEx);
          }
          default -> {
            // TIMEOUT — 5xx (and any unexpected non-success below 400).
            AiUnavailableException u =
                new AiUnavailableException("OpenAI 5xx: " + safeMessage(serviceEx), serviceEx);
            lastTransient = u;
            if (attempt == maxAttempts) {
              break;
            }
            sleepBackoff(RetryPolicy.Category.TIMEOUT, attempt, maxAttempts, "5xx", serviceEx);
            continue;
          }
        }
      } catch (OpenAIIoException transport) {
        // No HTTP status — transport failure → transient TIMEOUT, retried (parity with IOException
        // on the Anthropic path).
        AiUnavailableException u =
            new AiUnavailableException(
                "OpenAI transport failure: " + safeMessage(transport), transport);
        lastTransient = u;
        if (attempt == maxAttempts) {
          break;
        }
        sleepBackoff(RetryPolicy.Category.TIMEOUT, attempt, maxAttempts, "transport", transport);
      }
    }
    if (lastTransient instanceof AiRateLimitException) {
      throw new AiRateLimitException(
          "OpenAI rate-limited (429) after " + maxAttempts + " attempts", lastTransient);
    }
    throw new AiUnavailableException(
        "OpenAI chat call failed after " + maxAttempts + " attempts", lastTransient);
  }

  /**
   * Total wire attempts for one {@code chat}: the first attempt plus {@code
   * mealprep.ai.max-retries} retries — identical semantics to {@link AnthropicClient#maxAttempts()}
   * (the {@code AiProperties} record already floors a negative value to its default, so this is
   * always {@code >= 1}).
   */
  public int maxAttempts() {
    return 1 + Math.max(0, properties.maxRetries());
  }

  /**
   * Build the chat-completions request. The single rendered user message is the exact text {@link
   * AnthropicClient#renderUserMessage} produces, so the two providers send the same prompt body
   * (and {@code TokenCapGuard}'s estimate stays accurate). When the task declares a
   * structured-output schema, it is attached as a strict {@code json_schema} response format.
   */
  public ChatCompletionCreateParams buildParams(AiTask<?> task, String modelId) {
    ChatCompletionCreateParams.Builder builder =
        ChatCompletionCreateParams.builder()
            .model(modelId)
            .maxCompletionTokens(DEFAULT_MAX_COMPLETION_TOKENS)
            .addUserMessage(AnthropicClient.renderUserMessage(task, objectMapper));

    JsonNode schema = schemaFor(task);
    if (schema != null) {
      String schemaName = schemaName(task);
      ResponseFormatJsonSchema.JsonSchema.Schema.Builder schemaBuilder =
          ResponseFormatJsonSchema.JsonSchema.Schema.builder();
      // Each top-level schema field (type / properties / required / additionalProperties / ...) is
      // copied onto the SDK's Schema as an additional property — the SDK serialises it verbatim.
      Iterator<Map.Entry<String, JsonNode>> fields = schema.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        schemaBuilder.putAdditionalProperty(
            field.getKey(), JsonValue.fromJsonNode(field.getValue()));
      }
      ResponseFormatJsonSchema jsonSchema =
          ResponseFormatJsonSchema.builder()
              .jsonSchema(
                  ResponseFormatJsonSchema.JsonSchema.builder()
                      .name(schemaName)
                      .strict(true)
                      .schema(schemaBuilder.build())
                      .build())
              .build();
      builder.responseFormat(ChatCompletionCreateParams.ResponseFormat.ofJsonSchema(jsonSchema));
    }
    return builder.build();
  }

  /**
   * Extract the assistant message and usage. The model's structured-output JSON is the first
   * choice's message {@code content}; an OpenAI {@code refusal} (structured outputs can still
   * refuse) maps to {@link AiInvalidResponseException}. When a schema is present the body is
   * re-validated by {@link StructuredOutputParser} (then re-serialised so the dispatcher's
   * deserialise step sees canonical JSON); when absent, the raw body is returned for the dispatcher
   * to deserialise.
   */
  ChatResponse parse(
      ChatCompletion completion, String requestedModelId, JsonNode schema, Class<?> outputType) {
    List<ChatCompletion.Choice> choices = completion.choices();
    if (choices == null || choices.isEmpty()) {
      throw new AiInvalidResponseException("OpenAI chat response had no choices");
    }
    var message = choices.get(0).message();
    Optional<String> refusal = message.refusal();
    if (refusal.isPresent() && !refusal.get().isBlank()) {
      throw new AiInvalidResponseException("OpenAI model refused the request: " + refusal.get());
    }
    String body = message.content().orElse("");

    String modelId = completion.model();
    if (modelId == null || modelId.isBlank()) {
      modelId = requestedModelId;
    }
    Integer requestTokens = null;
    Integer responseTokens = null;
    Optional<CompletionUsage> usage = completion.usage();
    if (usage.isPresent()) {
      requestTokens = (int) Math.min(usage.get().promptTokens(), Integer.MAX_VALUE);
      responseTokens = (int) Math.min(usage.get().completionTokens(), Integer.MAX_VALUE);
    }

    if (schema != null) {
      // Defence-in-depth: re-validate against the same schema and canonicalise. Throws
      // AiInvalidResponseException on a schema miss or non-JSON body — same contract as prod's
      // structured-output path.
      JsonNode validated = structuredOutputParser.parse(body, schema, JsonNode.class);
      body = validated.toString();
    }
    return new ChatResponse(body, requestTokens, responseTokens, modelId);
  }

  /**
   * The task's structured-output JSON Schema, taken from its first {@link ToolDefinition}, or
   * {@code null} when the task expects free-text JSON.
   */
  private static JsonNode schemaFor(AiTask<?> task) {
    Optional<List<ToolDefinition>> tools = task.tools();
    if (tools.isEmpty() || tools.get().isEmpty()) {
      return null;
    }
    return tools.get().get(0).inputSchema();
  }

  /**
   * Schema name OpenAI requires on a {@code json_schema} response format — the tool name, else the
   * task type.
   */
  private static String schemaName(AiTask<?> task) {
    Optional<List<ToolDefinition>> tools = task.tools();
    if (tools.isPresent() && !tools.get().isEmpty()) {
      return tools.get().get(0).name();
    }
    return task.type().name().toLowerCase();
  }

  private void sleepBackoff(
      RetryPolicy.Category category, int attempt, int maxAttempts, String label, Throwable cause) {
    long delay = RetryPolicy.backoffWithJitter(category, attempt, jitterRandom).toMillis();
    log.warn(
        "openai chat {} (attempt {}/{}), backing off {}ms before retry: {}",
        label,
        attempt,
        maxAttempts,
        delay,
        cause.getMessage());
    sleepQuietly(delay);
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
}
