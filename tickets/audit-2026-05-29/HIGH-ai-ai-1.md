# AUDIT ai/ai-1 — Dispatcher never renders the prompt template and never sends a system prompt

| field | value |
|---|---|
| Severity | **HIGH** |
| Module | ai |
| Dimension | DESIGN_DIVERGENCE |
| Verification | verified-confirmed |
| **Triage (edit me)** | **FIX** |
| Source | design/audits/2026-05-29-v1-backend-conformance-audit.md |

## Where

src/main/java/com/example/mealprep/ai/domain/service/internal/AnthropicClient.java:129-177 (buildRequestBody / renderUserMessage); AiServiceImpl.java:106-107

## What's wrong

LLD Flow 1 steps 2-3 require 'PromptTemplateService.loadCurrent(ref) -> LoadedTemplate' then 'PromptTemplateRenderer.render(template, context)', and AiTask exposes getSystemPrompt() for 'Anthropic system param'. The shipped buildRequestBody builds only a single user message: it uses task.variables().get("prompt") if present, otherwise serialises the whole variables() map to JSON, otherwise falls back to task.prompt().name(). It sets NO `system` field on the request and never touches PromptTemplateService/PromptTemplateRenderer (confirmed by AiServiceImpl's constructor deps and grep). PromptTemplateServiceImpl.render(...) which assembles system+user+tools fully exists but is consumed only by the admin read endpoints. The renderer comment even admits 'for 01a the rendered prompt is the JSON of variables()... doesn't yet need the file-backed renderer (01d)'.

## Recommendation

Wire AiServiceImpl.execute to resolve the PromptRef via PromptTemplateService.render(ref, variables) and pass the rendered system + user messages (and tools/output schema) into AnthropicClient, so the file/DB-backed templates that the loader populates are actually used at dispatch. Until then, calling modules must hand-assemble the entire prompt into a `prompt` variable — verify every AiTask impl does so.
