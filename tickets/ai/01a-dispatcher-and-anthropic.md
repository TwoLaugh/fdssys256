# Ticket: ai — 01a Dispatcher + Anthropic Adapter + Call Log

## Summary

Foundation of the `ai` module: the `AiService` dispatcher, the SPI types every other module compiles against, the basic `AnthropicClient`, the `AiCallLog` audit trail, and the `TestAiService` that unblocks downstream module testing without touching the network. **Critical unblocker** — every Wave 2 module compiles against the SPI delivered here.

Defers to follow-ups: cost-cap state machine (01b), embedding pipeline (01c), prompt-template loading + admin endpoints (01d).

## Read these first

- [`lld/ai.md`](../../lld/ai.md) — module design. §Package Layout, §Database (V…__ai_create_call_log), §SPI, §Service Interfaces (`AiService`), §Configuration, §Test Plan are the relevant sections.
- [`lld/style-guide.md`](../../lld/style-guide.md) — project-wide conventions.
- [`lld/implementation-playbook.md`](../../lld/implementation-playbook.md) §Verification model — the agent self-verify loop you will run.
- [`lld/prompts/README.md`](../../lld/prompts/README.md) — prompt-design conventions; useful background on how downstream modules will call you.

Shape references in the existing codebase:
- `src/main/java/com/example/mealprep/auth/` — module layout (api/ + domain/ + config/ + event/ + exception/), MapStruct mapper, sealed event base
- `src/main/java/com/example/mealprep/core/audit/domain/service/internal/DecisionLogServiceImpl.java` — service-impl shape, JPA entity with `@CreationTimestamp` and `@Type(JsonBinaryType.class)` for JSONB columns
- `src/test/java/com/example/mealprep/auth/AuthServiceImplTest.java` — unit-test shape with mocked repos
- `src/test/java/com/example/mealprep/auth/RegisterFlowIT.java` — IT shape with Testcontainers

## Behavioural spec

### SPI types

1. `AiTask<T>` — generic interface. Methods: `TaskType type()`, `ModelTier tier()`, `PromptRef prompt()`, `Class<T> outputType()`, `Map<String, Object> variables()`, `Optional<List<ToolDefinition>> tools()`, `Optional<UUID> userId()`, `Optional<UUID> traceId()`. The `AiService` dispatches on the task; consumers implement.
2. `TaskType` enum — initially: `PREFERENCE_DELTA_UPDATE`, `INGREDIENT_MAPPING`, `INTAKE_PARSE`, `FEEDBACK_CLASSIFICATION`, `RECIPE_ADAPTATION`, `RECIPE_HTML_EXTRACTION`, `DISCOVERY_FILTERING`, `PLANNER_STAGE_C`, `PLANNER_PHASE2_AUGMENTATION` (one per prompt design under `lld/prompts/`).
3. `ModelTier` enum — `CHEAP`, `MID`, `HIGH`. Per [`lld/ai.md` §Configuration](../../lld/ai.md), maps to specific Anthropic model IDs via `AiConfig`.
4. `PromptRef` record — `(String name, int version)`. Refers to a prompt template; resolution via `PromptTemplateService` is 01d's job — for 01a, store the ref and pass through.
5. `ToolDefinition` record — `(String name, String description, JsonNode inputSchema)`. Used for tool-use prompts (Stage C, etc.). Pass through to Anthropic; 01a doesn't implement tool-result handling beyond serialise.

### `AiService` interface

```java
public interface AiService {
  /**
   * Dispatch a task. The implementation:
   *   1) records an AiCallLog row with status PENDING
   *   2) calls Anthropic via AnthropicClient
   *   3) updates the row to SUCCEEDED or FAILED
   *   4) deserialises the response per task.outputType()
   *   5) publishes AiCallSucceededEvent or AiCallFailedEvent AFTER_COMMIT
   *
   * Throws AiUnavailableException on transient errors after retry exhaustion.
   * Throws AiInvalidResponseException on malformed JSON.
   */
  <T> T execute(AiTask<T> task);
}
```

### Database — `ai_call_log`

6. Create migration `V20260601400000__ai_create_call_log.sql` (after preference's `V…1300000__`). Schema follows [`lld/ai.md` §V…__ai_create_call_log](../../lld/ai.md) — columns include `id, user_id, trace_id, task_type, model_tier, model_id, prompt_ref_name, prompt_ref_version, request_tokens, response_tokens, cost_micro_pence, status (PENDING|SUCCEEDED|FAILED), error_kind (nullable), latency_ms (nullable), created_at, completed_at (nullable)`. Indexes: `(user_id, created_at DESC)` partial WHERE user_id IS NOT NULL, `(trace_id)`, `(task_type, created_at DESC)`.
7. JPA entity `AiCallLog` — append-only, `@CreationTimestamp` on `created_at`, `@Type(JsonBinaryType.class)` on any JSONB columns, no `@Version` (audit row is never updated; the row is INSERTed PENDING then UPDATEd once to SUCCEEDED/FAILED via a service-level write — that one write is the only mutation).

### `AnthropicClient`

8. HTTP-based client (use Spring's `RestClient` from Spring 6 — no Anthropic-specific SDK dep needed; the API is straightforward JSON-over-HTTPS). Reads API key from `mealprep.ai.anthropic-api-key` property. Constructs `messages` request, applies retry (3 attempts, exponential backoff via Spring Retry — already on classpath via `spring-boot-starter-web`).
9. On non-2xx responses: log + throw `AiUnavailableException` with status code. On 4xx: throw `AiInvalidRequestException` (do not retry). On 5xx / network: retry, then throw `AiUnavailableException`.
10. Response parsing: extract `content[0].text` (and `tool_use` blocks if present). Pass to the task's `outputType()` deserialiser.

### `TestAiService` — test profile

11. `@Profile("test") @Primary @Service` bean replacing `AiServiceImpl` in test context. Per [`lld/ai.md` §Test Plan](../../lld/ai.md), it returns canned responses keyed by `TaskType`. Lookup table: `Map<TaskType, Object> cannedResponses` injected via setter or builder. Default: returns a stub for each TaskType that downstream module unit/IT tests can use without API calls.
12. `TestAiService` MUST NOT make HTTP calls. Verified by an IT that asserts no call log row gets a model_id starting with "claude-" when `TestAiService` is active.

### Cross-cutting

13. `AiException` module-root + subclasses: `AiUnavailableException` (5xx/network → 503), `AiInvalidRequestException` (4xx → 400), `AiInvalidResponseException` (malformed JSON → 502). Add handlers to `GlobalExceptionHandler`.
14. ArchUnit: add `aiReposAreInternalToAi` rule alongside the existing per-module rules in `ModuleBoundaryTest`.
15. Events: `AiCallSucceededEvent(callId, taskType, userId, latencyMs, costMicroPence, traceId)` and `AiCallFailedEvent(callId, taskType, userId, errorKind, traceId)` — both extend `core.events.ScopeChangedEvent` with `scopeKind="ai-call"`, `scopeId=callId`. Published `AFTER_COMMIT`.
16. `AiCallRecorder` (internal helper) wraps the INSERT-PENDING / UPDATE-final pattern in a `@Transactional(REQUIRES_NEW)` so the audit row survives a caller transaction rollback (same pattern as `core.audit.DecisionLogServiceImpl.write`).

## Configuration

```properties
# application.properties (defaults)
mealprep.ai.anthropic-api-key=               # set per-profile
mealprep.ai.anthropic-base-url=https://api.anthropic.com
mealprep.ai.tier-cheap-model=claude-haiku-4-5-20251001
mealprep.ai.tier-mid-model=claude-sonnet-4-6
mealprep.ai.tier-high-model=claude-opus-4-7
mealprep.ai.timeout-seconds=60
mealprep.ai.max-retries=3
```

Bind via `@ConfigurationProperties` record `AiProperties` in `auth/config` — mirror `AuthProperties`.

## OpenAPI updates

None for 01a — the dispatcher is internal-only (called by other backend modules, not exposed via REST). Admin endpoints land in 01d.

## Edge-case checklist

- [ ] `AiService.execute` records PENDING row even if the call fails (audit-trail invariant)
- [ ] Successful call updates the row to SUCCEEDED with `completed_at`, `latency_ms`, token counts, cost
- [ ] Failed call updates to FAILED with `error_kind` set (`AI_UNAVAILABLE` | `INVALID_REQUEST` | `INVALID_RESPONSE`)
- [ ] `REQUIRES_NEW` propagation verified — caller tx rollback leaves the call-log row in place
- [ ] `TestAiService` is the active bean in tests; ITs assert no HTTP call leaves the JVM
- [ ] Anthropic 4xx not retried; 5xx retried up to `max-retries` times
- [ ] Retries respect timeout per attempt
- [ ] Tool-use response parses (`tool_use` blocks come through; 01a doesn't ACT on them — just deserialises)
- [ ] `traceId` propagates from the task to the call-log row
- [ ] No raw API key in logs (mask via SLF4J custom layout or just don't log it)
- [ ] ArchUnit: no cross-module repo imports

## Files this ticket touches

```
src/main/resources/db/migration/V20260601400000__ai_create_call_log.sql                new
src/main/java/com/example/mealprep/ai/AiModule.java                                    new (facade)
src/main/java/com/example/mealprep/ai/spi/AiTask.java                                  new
src/main/java/com/example/mealprep/ai/spi/TaskType.java                                new (enum)
src/main/java/com/example/mealprep/ai/spi/ModelTier.java                               new (enum)
src/main/java/com/example/mealprep/ai/spi/PromptRef.java                               new (record)
src/main/java/com/example/mealprep/ai/spi/ToolDefinition.java                          new (record)
src/main/java/com/example/mealprep/ai/api/dto/AiCallLogDto.java                        new (record)
src/main/java/com/example/mealprep/ai/api/mapper/AiCallLogMapper.java                  new (MapStruct)
src/main/java/com/example/mealprep/ai/domain/entity/AiCallLog.java                     new
src/main/java/com/example/mealprep/ai/domain/entity/CallStatus.java                    new (enum)
src/main/java/com/example/mealprep/ai/domain/entity/CallErrorKind.java                 new (enum)
src/main/java/com/example/mealprep/ai/domain/repository/AiCallLogRepository.java       new
src/main/java/com/example/mealprep/ai/domain/service/AiService.java                    new (interface)
src/main/java/com/example/mealprep/ai/domain/service/internal/AiServiceImpl.java       new
src/main/java/com/example/mealprep/ai/domain/service/internal/AnthropicClient.java     new
src/main/java/com/example/mealprep/ai/domain/service/internal/AiCallRecorder.java      new
src/main/java/com/example/mealprep/ai/event/AiCallSucceededEvent.java                  new
src/main/java/com/example/mealprep/ai/event/AiCallFailedEvent.java                     new
src/main/java/com/example/mealprep/ai/exception/AiException.java                       new (module root)
src/main/java/com/example/mealprep/ai/exception/AiUnavailableException.java            new
src/main/java/com/example/mealprep/ai/exception/AiInvalidRequestException.java         new
src/main/java/com/example/mealprep/ai/exception/AiInvalidResponseException.java        new
src/main/java/com/example/mealprep/ai/config/AiProperties.java                         new (@ConfigurationProperties record)
src/main/java/com/example/mealprep/ai/config/AnthropicSdkConfig.java                   new (RestClient bean)
src/main/java/com/example/mealprep/ai/testing/TestAiService.java                       new (@Profile("test") @Primary)
src/main/resources/application.properties                                              modified (add AI defaults)
src/main/resources/application-dev.properties                                          modified
src/main/resources/application-prod.properties                                         modified
src/main/resources/application-test.properties                                         modified (test API key stub)
src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java                  modified (3 new handlers)
src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java                    modified (add aiReposAreInternalToAi)
src/test/java/com/example/mealprep/ai/AnthropicClientTest.java                         new (unit; mocks RestClient)
src/test/java/com/example/mealprep/ai/AiServiceImplTest.java                           new (unit; mocks AnthropicClient + repo)
src/test/java/com/example/mealprep/ai/AiCallLogIT.java                                 new (Testcontainers; REQUIRES_NEW survival, JSONB roundtrip)
src/test/java/com/example/mealprep/ai/TestAiServiceIT.java                             new (Testcontainers; verifies no HTTP leaves)
src/test/java/com/example/mealprep/ai/testdata/AiTestData.java                         new (Test Data Builder)
```

## Dependencies

- `core-01-decision-log` (merged) — uses `core.events.ScopeChangedEvent` for the AI events.
- No other hard deps. Unblocks every Wave 2 module that compiles against the SPI.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` passes
- [ ] CI green on the PR (build + spotless + OpenAPI lint + pitest)
- [ ] Mutation score ≥70% on `AnthropicClient`, `AiServiceImpl`, `AiCallRecorder`
- [ ] `TestAiService` IT confirms no HTTP egress in test mode
- [ ] Edge-case checklist all ticked

Squash-merge with: `feat(ai): 01a — dispatcher + Anthropic adapter + call log + SPI`
