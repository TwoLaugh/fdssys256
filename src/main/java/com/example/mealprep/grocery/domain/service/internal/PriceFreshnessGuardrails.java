package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.service.AiCostTrackingService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Cost-cap-aware pre-flight for the Tier-4 refresh paths (grocery-01g). Centralises the {@code
 * ALLOW | SKIP | BLOCK} decision so the {@code @Scheduled} weekly refresh and the on-demand REST
 * path read from the same source of truth.
 *
 * <p>Per lld/grocery.md lines 52-53 / 953. The two decision dimensions are the {@link RefreshKind}
 * (who initiated the call) and the AI cost-cap state:
 *
 * <ul>
 *   <li><b>Daily cap approached</b> → {@code SKIP} for {@link RefreshKind#SCHEDULED} (HLD:
 *       scheduled refresh is the FIRST thing skipped when the cap is approached); {@code ALLOW} for
 *       {@link RefreshKind#ON_DEMAND} (the user invoked it deliberately — {@code
 *       AiUnavailableException} will surface naturally per 01c).
 *   <li><b>Monthly cap fired</b> → {@code BLOCK} everywhere (all users skip until reset). v1 ships
 *       with the AI module's daily rolling window as the only enforced cap; the monthly-cap
 *       distinction is reserved for v2 (`mealprep.ai.budget` only exposes the daily window today).
 *   <li>Otherwise → {@code ALLOW}.
 * </ul>
 *
 * <p><b>LLD divergence (grocery-01g).</b> The LLD names an {@code
 * AiCostBudgetService.hasDailyCapacity(userId)} method on the ai module's public surface; the
 * shipped ai module exposes the same information through {@link
 * AiCostTrackingService#pencesSpentBy} + {@link AiProperties.Budget#dailyPencePerUser()} instead.
 * This guardrail composes those two surfaces to compute "approached" / "not approached". Both
 * injections are wrapped in {@link ObjectProvider} so the guardrail still loads when either surface
 * is absent (the degraded-fallback path documented in the ticket): without the cost- tracking
 * surface we conservatively {@code SKIP} the scheduled refresh and {@code ALLOW} the on-demand
 * path, gated by the project-wide {@code mealprep.ai.budget.enabled} flag (when the budget feature
 * is disabled we always {@code ALLOW}).
 *
 * <p>"Approached" is defined as {@code spend >= dailyPencePerUser * APPROACH_THRESHOLD_PERCENT /
 * 100}. The HLD's "approached" wording is deliberately fuzzy — a fixed 80% threshold mirrors the
 * spirit ("skip BEFORE the cap fires, not just when it has") without adding a new tunable.
 */
@Component
public class PriceFreshnessGuardrails {

  /** Refresh request origin — drives the daily-cap branch. */
  public enum RefreshKind {
    SCHEDULED,
    ON_DEMAND
  }

  /** Decision returned by {@link #preflight(UUID, RefreshKind)}. */
  public enum Decision {
    ALLOW,
    SKIP,
    BLOCK
  }

  /**
   * Threshold (percentage of the daily cap) above which "approached" trips. 80% mirrors the HLD's
   * "scheduled refresh is the FIRST thing skipped as the cap is approached" wording.
   */
  static final int APPROACH_THRESHOLD_PERCENT = 80;

  private static final Logger log = LoggerFactory.getLogger(PriceFreshnessGuardrails.class);

  private final ObjectProvider<AiCostTrackingService> costTrackingProvider;
  private final ObjectProvider<AiProperties> aiPropertiesProvider;

  public PriceFreshnessGuardrails(
      ObjectProvider<AiCostTrackingService> costTrackingProvider,
      ObjectProvider<AiProperties> aiPropertiesProvider) {
    this.costTrackingProvider = costTrackingProvider;
    this.aiPropertiesProvider = aiPropertiesProvider;
  }

  /**
   * Evaluate the cost-cap state for {@code userId} and return the {@link Decision} the caller
   * should honour. Read-only — never mutates state.
   */
  public Decision preflight(UUID userId, RefreshKind kind) {
    AiCostTrackingService tracking = costTrackingProvider.getIfAvailable();
    AiProperties properties = aiPropertiesProvider.getIfAvailable();

    // Degraded-fallback path (LLD divergence note): without the AI cost surfaces we cannot read
    // capacity, so the conservative default is to SKIP scheduled work and ALLOW on-demand —
    // identical to the "feature flag off → conservative" rule the ticket documents.
    if (tracking == null || properties == null || properties.budget() == null) {
      log.debug(
          "PriceFreshnessGuardrails: AI cost surfaces unavailable — degrading to conservative"
              + " (SKIP scheduled, ALLOW on-demand) for user {}",
          userId);
      return kind == RefreshKind.SCHEDULED ? Decision.SKIP : Decision.ALLOW;
    }

    AiProperties.Budget budget = properties.budget();
    if (!Boolean.TRUE.equals(budget.enabled())) {
      // Cost cap disabled (dev / test convenience) → no gating from this guardrail.
      return Decision.ALLOW;
    }

    long limitPence = budget.dailyPencePerUser();
    if (limitPence <= 0) {
      // A non-positive limit means "no spending allowed" — both kinds blocked.
      return Decision.BLOCK;
    }

    Duration window = budget.window();
    BigDecimal spent;
    try {
      spent = tracking.pencesSpentBy(userId, window);
    } catch (RuntimeException ex) {
      // Read-side failure must not crash a scheduled run; degrade per the ticket's fallback.
      log.warn(
          "PriceFreshnessGuardrails: cost tracking read failed for user {}: {}",
          userId,
          ex.getMessage());
      return kind == RefreshKind.SCHEDULED ? Decision.SKIP : Decision.ALLOW;
    }
    if (spent == null) {
      return Decision.ALLOW;
    }

    long spentPence = spent.longValue();
    long approachedAt = approachedThreshold(limitPence);

    if (spentPence >= limitPence) {
      // Cap reached — the on-demand path will trip AiUnavailableException naturally; we explicitly
      // SKIP the scheduled call so it doesn't even attempt the provider quote.
      return kind == RefreshKind.SCHEDULED ? Decision.SKIP : Decision.ALLOW;
    }
    if (spentPence >= approachedAt) {
      // Approaching cap — scheduled refresh defers; on-demand still ALLOWed.
      return kind == RefreshKind.SCHEDULED ? Decision.SKIP : Decision.ALLOW;
    }
    return Decision.ALLOW;
  }

  /**
   * Spending threshold (in pence) above which we consider the cap "approached". Package-private so
   * the unit test can assert the boundary directly.
   */
  static long approachedThreshold(long limitPence) {
    // Equivalent to floor(limit * percent / 100). Multiplied first to avoid losing precision on
    // small daily caps (the default is 50p — 80% of 50 is 40, exactly representable).
    return Math.floorDiv(limitPence * APPROACH_THRESHOLD_PERCENT, 100L);
  }
}
