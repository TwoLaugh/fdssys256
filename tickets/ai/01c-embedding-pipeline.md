# Ticket: ai — 01c Embedding Pipeline (OpenAI + pgvector)

## Summary

Add the embedding side of the AI module: an `EmbeddingTask` SPI, an `OpenAiEmbeddingClient` (model: `text-embedding-3-small`, 1536 dim), and `AiServiceImpl.embed`. Embeddings land in pgvector columns owned by downstream modules (`preference.taste_vector`, `recipe.semantic_vector`, etc.) — this ticket only delivers the production-side path; consumers wire it up in their own tickets.

## Read these first

- [`tickets/ai/01a-dispatcher-and-anthropic.md`](01a-dispatcher-and-anthropic.md) — the foundation. You add a sibling method `embed` to `AiServiceImpl`; do NOT modify `execute` (01b's territory).
- [`lld/ai.md`](../../lld/ai.md) — §`EmbeddingTask` SPI, §Service Interfaces, §Configuration (OpenAI section), §Test Plan.
- [`lld/preference.md`](../../lld/preference.md#L132) — context: `preference_taste_profile.taste_vector` is a downstream consumer. Read this for what shape consumers expect (1536-dim vector + status column + last-embedded timestamp).
- [`lld/style-guide.md`](../../lld/style-guide.md) — conventions.
- [`lld/implementation-playbook.md`](../../lld/implementation-playbook.md) §Verification model.

Shape references:
- `core/audit/domain/service/internal/DecisionLogServiceImpl.java` — service shape
- `auth/domain/service/internal/SessionTokenGenerator.java` — small focused internal class

## Behavioural spec

### `EmbeddingTask` SPI

```java
public interface EmbeddingTask {
  /** Stable identifier so the call log distinguishes embedding sources. */
  EmbeddingTaskType type();

  /** Text to embed. The caller is responsible for whatever pre-processing they need. */
  String inputText();

  /** Optional userId — present on user-scoped embeddings (taste profile, journal entries). */
  Optional<UUID> userId();

  /** Optional traceId. */
  Optional<UUID> traceId();
}
```

`EmbeddingTaskType` enum — initially: `PREFERENCE_TASTE_VECTOR`, `RECIPE_SEMANTIC_VECTOR`, `JOURNAL_ENTRY_VECTOR`. Extend as new consumers come online.

### `AiService.embed`

1. Add to the `AiService` interface (already public from 01a):
   ```java
   float[] embed(EmbeddingTask task);
   ```
2. Returns a 1536-dimension `float[]`. Caller is responsible for storing it (typically into a `vector(1536)` column via hypersistence-utils-pgvector).
3. Records an `AiCallLog` row with `task_type` = `EMBEDDING_<EmbeddingTaskType>` (extend the existing 01a `TaskType` enum with these — or use a separate `embedding_task_type` column; either works, document the choice). `model_id = "text-embedding-3-small"`. `model_tier = CHEAP` (embeddings are by far the cheapest per-call AI op).

### `OpenAiEmbeddingClient` (internal)

4. Use the `openai-java` SDK already in the pom (`com.openai:openai-java:0.20.0`). Construct a single `OpenAIClient` bean from `mealprep.ai.openai-api-key`. The model is `text-embedding-3-small`.
5. On non-2xx: same retry policy as `AnthropicClient` from 01a (3 attempts, exponential backoff). On exhaust: `AiUnavailableException`.
6. Caching: embeddings of identical input text MUST be cached for 24h to avoid duplicate spend on idempotent re-embeds. Use a simple in-memory `Caffeine` cache (`com.github.ben-manes.caffeine:caffeine` is on the classpath via Spring Boot starters). Key: `sha256(inputText) + model`. Value: `float[]`. Size cap: 10,000 entries; expire 24h after write.

### `TestAiService.embed`

7. Per the `TestAiService` pattern from 01a — return canned 1536-dim float arrays in test mode. Default: a deterministic vector derived from `inputText.hashCode()` (NOT random — tests must be reproducible). Override in specific tests via setter.

### Cross-cutting

8. New `EMBEDDING` value in the `TaskType` enum (or add `embedding_task_type` column to `ai_call_log`; ticket-author choice — document inline). Embeddings appear in the call log alongside Anthropic calls; cost-tracking from 01b automatically includes them.
9. No new exceptions needed — reuse `AiUnavailableException` / `AiInvalidResponseException` from 01a.

## Configuration additions

```properties
mealprep.ai.openai-api-key=                # already exists; document
mealprep.ai.embedding-model=text-embedding-3-small
mealprep.ai.embedding-cache-size=10000
mealprep.ai.embedding-cache-ttl-hours=24
```

## Edge-case checklist

- [ ] Empty input string → `IllegalArgumentException` (don't waste a call)
- [ ] Cache hit returns identical `float[]` instance — verified via instance-equality assert in test
- [ ] Cache miss-then-hit pattern: same input twice → only ONE Anthropic-API-side call recorded in `ai_call_log`
- [ ] Different models cache separately (key includes model name)
- [ ] `TestAiService.embed` returns deterministic vectors — same input = same output
- [ ] `TestAiService.embed` does NOT make HTTP calls — verified by IT
- [ ] Cost-tracking from 01b counts embeddings in the per-user budget (since they go through the same call-log path)
- [ ] Mutation score ≥70% on `OpenAiEmbeddingClient`

## Files this ticket touches

```
src/main/java/com/example/mealprep/ai/spi/EmbeddingTask.java                            new (interface)
src/main/java/com/example/mealprep/ai/spi/EmbeddingTaskType.java                        new (enum)
src/main/java/com/example/mealprep/ai/domain/service/AiService.java                     modified (add embed method)
src/main/java/com/example/mealprep/ai/domain/service/internal/AiServiceImpl.java        modified (implement embed)
src/main/java/com/example/mealprep/ai/domain/service/internal/OpenAiEmbeddingClient.java new
src/main/java/com/example/mealprep/ai/config/OpenAiSdkConfig.java                       new (OpenAIClient bean)
src/main/java/com/example/mealprep/ai/config/AiProperties.java                          modified (embedding sub-record)
src/main/java/com/example/mealprep/ai/testing/TestAiService.java                        modified (implement embed)
src/main/resources/application.properties                                               modified (embedding config)
src/test/java/com/example/mealprep/ai/OpenAiEmbeddingClientTest.java                    new (unit; mocks SDK)
src/test/java/com/example/mealprep/ai/AiServiceImplTest.java                            modified (add embed cases)
src/test/java/com/example/mealprep/ai/EmbeddingPipelineIT.java                          new (Testcontainers; cache hit/miss + log row)
```

## Dependencies

- **Hard dependency**: `ai-01a`. Modifies `AiServiceImpl` and `TestAiService` from 01a.
- **No dependency on 01b or 01d**.
- **No conflict with 01b**: 01b modifies `execute`; 01c adds `embed`. Different methods on the same class.
- **No conflict with 01d**: 01d touches `PromptTemplate*` files and admin endpoints; this ticket touches the embedding stack.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally
- [ ] CI green on the PR
- [ ] Mutation score ≥70% on `OpenAiEmbeddingClient`
- [ ] Edge-case checklist all ticked

Squash-merge with: `feat(ai): 01c — OpenAI embedding pipeline + cache + EmbeddingTask SPI`
