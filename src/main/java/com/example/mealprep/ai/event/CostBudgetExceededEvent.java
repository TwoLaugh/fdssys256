package com.example.mealprep.ai.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when {@code CostBudgetGuard} detects a cost-cap breach. Carries
 * the {@link BudgetScope} that tripped and whether that scope is a hard block (call rejected) or a
 * soft alert (call proceeded) — see lld/ai.md §Events / Flow 1 step 5.
 *
 * <p>Implements {@link ScopeChangedEvent} with {@code scopeKind="ai-budget"} so downstream
 * listeners (notification module, grocery's scheduled-refresh pause) can react without coupling to
 * the AI module's concrete event type.
 *
 * <ul>
 *   <li><b>DAILY_USER</b> — {@code userId} is the affected user; {@code window} is the rolling
 *       daily window. {@code hardBlock} is {@code false} by default (alert-and-proceed).
 *   <li><b>MONTHLY_TOTAL</b> — system-wide breach; {@code userId} is {@code null} (it bills the
 *       system) and {@code scopeId()} returns the {@link #SYSTEM_SCOPE_ID} sentinel; {@code window}
 *       is the rolling monthly window. {@code hardBlock} is {@code true} by default.
 * </ul>
 *
 * <p>{@code spentPence} / {@code limitPence} are pence (not micropence) for human-readable
 * downstream formatting.
 */
public record CostBudgetExceededEvent(
    UUID userId,
    BudgetScope scope,
    boolean hardBlock,
    BigDecimal spentPence,
    BigDecimal limitPence,
    Duration window,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  /**
   * Stable scope-id used for system-wide ({@code MONTHLY_TOTAL}) breaches that have no {@code
   * userId}. Deterministic so a listener can recognise / debounce the system budget scope.
   */
  public static final UUID SYSTEM_SCOPE_ID =
      UUID.nameUUIDFromBytes(
          "ai-budget:MONTHLY_TOTAL".getBytes(java.nio.charset.StandardCharsets.UTF_8));

  /**
   * Backward-compatible per-user constructor (pre-two-scope callers / tests). Defaults to {@code
   * scope=DAILY_USER}, {@code hardBlock=true} — the historical single-window hard-per-user shape.
   */
  public CostBudgetExceededEvent(
      UUID userId,
      BigDecimal spentPence,
      BigDecimal limitPence,
      Duration window,
      UUID traceId,
      Instant occurredAt) {
    this(userId, BudgetScope.DAILY_USER, true, spentPence, limitPence, window, traceId, occurredAt);
  }

  @Override
  public String scopeKind() {
    return "ai-budget";
  }

  @Override
  public UUID scopeId() {
    return userId != null ? userId : SYSTEM_SCOPE_ID;
  }
}
