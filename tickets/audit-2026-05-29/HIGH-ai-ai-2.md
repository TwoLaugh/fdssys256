# AUDIT ai/ai-2 — No circuit breaker / Resilience4j; retry classification collapsed to 4xx-vs-5xx

| field | value |
|---|---|
| Severity | **HIGH** |
| Module | ai |
| Dimension | DESIGN_DIVERGENCE |
| Verification | verified-confirmed |
| **Triage (edit me)** | **FIX-OR-ACCEPT** |
| Source | design/audits/2026-05-29-v1-backend-conformance-audit.md |

## Where

src/main/java/com/example/mealprep/ai/domain/service/internal/AnthropicClient.java:67-95; module-wide grep for Resilience4j/CircuitBreaker = no matches

## What's wrong

LLD Flow 2 + Concurrency table pin Resilience4j @CircuitBreaker(name="ai-${taskType}") (open after 5 failures, 5min window) and @Retry/@RateLimiter on ToolUseInvoker.call, plus a RetryPolicy class classifying TIMEOUT/RATE_LIMIT/SEMANTIC/AUTH/POLICY/UNKNOWN with distinct strategies, and AiCircuitOpenException (503). The shipped AnthropicClient hand-rolls a 3-attempt loop that retries only AiUnavailableException(5xx)/ResourceAccessException and treats ALL 4xx (including 429 RATE_LIMIT, which the LLD says to retry with longer backoff) as fatal AiInvalidRequestException. There is no circuit breaker, no RateLimiter, no AiCircuitOpenException, and no RetryPolicy type anywhere in the module.

## Recommendation

Either implement the LLD's Resilience4j circuit breaker + 429 retry handling, or explicitly down-scope these from v1 in lld/ai.md. At minimum 429s should be retried (currently a rate-limited burst fails fast as INVALID_REQUEST).
