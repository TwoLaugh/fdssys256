# Ticket: ai — 01d Prompt Templates + Admin Endpoints

## Summary

Add the `PromptTemplate` storage + loader + render service that the 01a SPI's `PromptRef` resolves through, plus the `ToolUseInvoker` and `StructuredOutputParser` helpers that downstream tool-use prompts (Stage C, etc.) need. Add three admin REST endpoints for cost/call-log/template observability. Mostly independent of 01b/01c — touches different files.

## Read these first

- [`tickets/ai/01a-dispatcher-and-anthropic.md`](01a-dispatcher-and-anthropic.md) — the foundation.
- [`lld/ai.md`](../../lld/ai.md) §`PromptTemplateService`, §V…__ai_create_prompt_template, §REST Controllers, §Validation.
- [`lld/prompts/README.md`](../../lld/prompts/README.md) — prompt-design conventions: confidence scale, null-population rules, edge-case-mandatory, cache strategy, TaskType banner. The loader must understand the `lld/prompts/*.md` shape.
- [`lld/prompts/01-taste-profile-delta.md`](../../lld/prompts/01-taste-profile-delta.md) — concrete example of a mid-tier prompt.
- [`lld/prompts/08-planner-stage-c.md`](../../lld/prompts/08-planner-stage-c.md) — concrete example of a tool-use prompt.
- [`lld/style-guide.md`](../../lld/style-guide.md) — conventions.
- [`lld/implementation-playbook.md`](../../lld/implementation-playbook.md) §Verification model.

Shape references:
- `core/audit/api/controller/AdminDecisionLogController.java` — admin endpoint shape
- `auth/api/controller/AuthController.java` — controller layout / cookie auth
- `core/audit/domain/service/internal/DecisionLogServiceImpl.java` — read-side service shape

## Behavioural spec

### Database — `ai_prompt_template`

1. Migration `V20260601400100__ai_create_prompt_template.sql`. Schema follows [`lld/ai.md` §V…__ai_create_prompt_template](../../lld/ai.md): `id, name, version, model_tier, system_prompt, user_prompt_template, output_schema (jsonb, nullable), tools (jsonb, nullable), notes, source_file, source_hash, created_at`. Unique on `(name, version)`. Index `(name, version DESC)`.
2. JPA entity `PromptTemplate` — append-only (no @Version, no setters except for Hibernate). JSONB columns via `@Type(JsonBinaryType.class)`.

### `PromptTemplateLoader`

3. On startup, scan `classpath:lld/prompts/*.md` (resources or classpath plugin). Parse the front-matter table (the markdown table with `AiTask name | TaskType | Tier | ...` rows) plus the prompt body. For each file:
   - Compute `sha256` of the file bytes → `source_hash`.
   - Look up by `(name, latest version)`. If `source_hash` matches, no-op.
   - If different (or absent): INSERT a new row with `version = max(existing) + 1` (or 1 if none), capturing the system prompt, user prompt template, output schema (parsed from a fenced code block per convention), tool defs.
4. Loader runs in `@PostConstruct` of `PromptTemplateLoader`. Failures are logged at WARN; the service continues — production callers fall back to in-memory templates if DB load failed.
5. Make the `lld/prompts/` files available on the test classpath via `src/test/resources/prompts/` symlink-or-copy approach. **OR** read them from the project filesystem at startup with the path configurable. Document the choice.

### `PromptTemplateService`

```java
public interface PromptTemplateService {
  PromptTemplateDto get(String name, int version);
  PromptTemplateDto getLatest(String name);
  Page<PromptTemplateDto> listAll(Pageable pageable);

  /** Render a template into a final prompt string + tool-defs given variables. */
  RenderedPrompt render(PromptRef ref, Map<String, Object> variables);
}

public record RenderedPrompt(
    String systemPrompt,
    String userPrompt,
    List<ToolDefinition> tools,
    JsonNode expectedOutputSchema   // null for free-text outputs
) {}
```

6. `render` does Mustache-style `{{variable}}` substitution. Missing variables → `IllegalArgumentException` (don't silently ship `{{undefined}}` to Anthropic).

### `ToolUseInvoker` + `StructuredOutputParser` (internal helpers)

7. `ToolUseInvoker` — given a tool name + JSON args, dispatch to a registered handler. For 01d, register a NO-OP handler (logs the call). Real handlers register from downstream modules in their own tickets (planner, recipe, etc.). The plumbing is here; the implementations come later.
8. `StructuredOutputParser` — given the response and the expected `output_schema` (JSON Schema), validate + deserialise. On schema mismatch: `AiInvalidResponseException`. Use `networknt/json-schema-validator` (already a transitive dep).

### Admin REST endpoints

`@PreAuthorize("hasRole('ADMIN')")` on each — gating activates with `auth-roles-followup` (per the existing TODO marker pattern from core-01).

| Method | Path | Returns |
|---|---|---|
| GET | `/api/v1/admin/ai/cost-summary?windowHours=24` | Sum across all users + per-user breakdown for top 20 spenders |
| GET | `/api/v1/admin/ai/call-log?page=&size=&taskType=&userId=` | `Page<AiCallLogDto>`, newest-first |
| GET | `/api/v1/admin/ai/prompt-templates?page=&size=` | `Page<PromptTemplateDto>` |
| GET | `/api/v1/admin/ai/prompt-templates/{name}/{version}` | Single template |

### Cross-cutting

9. ArchUnit: no new rules — admin controllers fall under existing `..api..` rule.
10. OpenAPI: add the 4 endpoints + 2 schemas (`AiCallLogDto`, `PromptTemplateDto`) under existing `paths:` and `components:` blocks. `cookieAuth` security on each.
11. `PromptTemplateLoadedEvent` — published on each successful template version insert. Useful for cache invalidation downstream. Implements `ScopeChangedEvent` with `scopeKind="prompt-template"`, `scopeId=template.id`.

## Edge-case checklist

- [ ] Loader detects no-change (same hash) and skips INSERT
- [ ] Loader detects content change → INSERTs a new version row, leaves old one in place
- [ ] Loader handles missing `lld/prompts/` directory at startup (logs WARN, continues)
- [ ] `render` rejects missing variables (no silent `{{x}}` in output)
- [ ] `render` substitutes Mustache-style placeholders correctly (escape edge cases: variable value containing `{{`/`}}`)
- [ ] `StructuredOutputParser` rejects extra/missing fields per schema
- [ ] Admin GET endpoints return 401 without cookie (filter chain handles)
- [ ] OpenAPI request/response schemas match (contract test)
- [ ] No raw API key leaks in any log
- [ ] Mutation score ≥70% on `PromptTemplateLoader` and `PromptTemplateRenderer`

## Files this ticket touches

```
src/main/resources/db/migration/V20260601400100__ai_create_prompt_template.sql        new
src/main/java/com/example/mealprep/ai/api/controller/AdminAiController.java           new
src/main/java/com/example/mealprep/ai/api/dto/AiCallLogDto.java                       (already from 01a; modify only if needed)
src/main/java/com/example/mealprep/ai/api/dto/PromptTemplateDto.java                  new (record)
src/main/java/com/example/mealprep/ai/api/dto/CostSummaryDto.java                     new (record)
src/main/java/com/example/mealprep/ai/api/mapper/PromptTemplateMapper.java            new (MapStruct)
src/main/java/com/example/mealprep/ai/domain/entity/PromptTemplate.java               new
src/main/java/com/example/mealprep/ai/domain/repository/PromptTemplateRepository.java new
src/main/java/com/example/mealprep/ai/domain/service/PromptTemplateService.java       new (interface)
src/main/java/com/example/mealprep/ai/domain/service/internal/PromptTemplateServiceImpl.java   new
src/main/java/com/example/mealprep/ai/domain/service/internal/PromptTemplateLoader.java       new (@PostConstruct)
src/main/java/com/example/mealprep/ai/domain/service/internal/PromptTemplateRenderer.java     new (Mustache substitution)
src/main/java/com/example/mealprep/ai/domain/service/internal/ToolUseInvoker.java             new
src/main/java/com/example/mealprep/ai/domain/service/internal/StructuredOutputParser.java     new
src/main/java/com/example/mealprep/ai/event/PromptTemplateLoadedEvent.java                    new
src/main/resources/openapi/openapi.yaml                                                       modified (4 paths + 2 schemas)
src/test/java/com/example/mealprep/ai/PromptTemplateLoaderTest.java                           new (unit; reads test fixtures)
src/test/java/com/example/mealprep/ai/PromptTemplateRendererTest.java                         new (unit)
src/test/java/com/example/mealprep/ai/StructuredOutputParserTest.java                         new (unit)
src/test/java/com/example/mealprep/ai/AdminAiControllerIT.java                                new (Testcontainers; contract test)
src/test/resources/prompts/test-template.md                                                   new (fixture for loader tests)
```

## Dependencies

- **Hard dependency**: `ai-01a`. Reuses `AiCallLog` entity, `AiCallLogDto`, `TaskType`, `ModelTier`, `AnthropicClient` from 01a.
- **No dependency on 01b or 01c**.
- **No conflict with 01b**: this ticket adds NEW classes + endpoints; 01b modifies `AiServiceImpl.execute`. Disjoint.
- **No conflict with 01c**: 01c adds the `embed` method on `AiServiceImpl`; this ticket doesn't touch it. Disjoint.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally
- [ ] CI green on the PR
- [ ] Mutation score ≥70% on loader, renderer, parser
- [ ] Admin endpoint contract tests green
- [ ] Edge-case checklist all ticked

Squash-merge with: `feat(ai): 01d — prompt template loader + render service + admin endpoints`
