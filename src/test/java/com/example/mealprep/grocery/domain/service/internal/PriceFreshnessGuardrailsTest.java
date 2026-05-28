package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.service.AiCostTrackingService;
import com.example.mealprep.grocery.domain.service.internal.PriceFreshnessGuardrails.Decision;
import com.example.mealprep.grocery.domain.service.internal.PriceFreshnessGuardrails.RefreshKind;
import com.example.mealprep.grocery.domain.service.internal.testsupport.EmptyObjectProvider;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit coverage for {@link PriceFreshnessGuardrails}. Exercises the four cost-cap branches the 01g
 * ticket calls out: well-under-cap → ALLOW for both kinds; cap approached (≥ 80%) → SKIP for
 * SCHEDULED, ALLOW for ON_DEMAND; cap reached → SKIP for SCHEDULED, ALLOW for ON_DEMAND (the
 * on-demand path lets {@code AiUnavailableException} surface naturally per 01c); cost surfaces
 * absent → degraded fallback (SKIP scheduled, ALLOW on-demand). Also covers the boundary helper
 * {@link PriceFreshnessGuardrails#approachedThreshold(long)} directly.
 */
class PriceFreshnessGuardrailsTest {

  private static final UUID USER = UUID.randomUUID();

  private static AiProperties.Budget budget(boolean enabled, long dailyPence, int windowHours) {
    return new AiProperties.Budget(enabled, dailyPence, windowHours);
  }

  private static AiProperties propertiesWith(AiProperties.Budget budget) {
    AiProperties p = mock(AiProperties.class);
    when(p.budget()).thenReturn(budget);
    return p;
  }

  private static <T> ObjectProvider<T> providerOf(T instance) {
    ObjectProvider<T> mock = mockProvider();
    when(mock.getIfAvailable()).thenReturn(instance);
    return mock;
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> mockProvider() {
    return mock(ObjectProvider.class);
  }

  private static AiCostTrackingService trackingReturning(long pence) {
    AiCostTrackingService svc = mock(AiCostTrackingService.class);
    when(svc.pencesSpentBy(any(UUID.class), any(Duration.class)))
        .thenReturn(BigDecimal.valueOf(pence));
    return svc;
  }

  // ---- happy path: well under cap → ALLOW for both kinds ----

  @Test
  void wellUnderCap_allowsBothKinds() {
    PriceFreshnessGuardrails g =
        new PriceFreshnessGuardrails(
            providerOf(trackingReturning(10L)), providerOf(propertiesWith(budget(true, 50L, 24))));

    assertThat(g.preflight(USER, RefreshKind.SCHEDULED)).isEqualTo(Decision.ALLOW);
    assertThat(g.preflight(USER, RefreshKind.ON_DEMAND)).isEqualTo(Decision.ALLOW);
  }

  // ---- approaching cap: scheduled SKIPs, on-demand still ALLOWed ----

  @Test
  void approachingCap_skipsScheduled_allowsOnDemand() {
    // 80% of 50p = 40p — exactly at the threshold trips the "approached" branch.
    PriceFreshnessGuardrails g =
        new PriceFreshnessGuardrails(
            providerOf(trackingReturning(40L)), providerOf(propertiesWith(budget(true, 50L, 24))));

    assertThat(g.preflight(USER, RefreshKind.SCHEDULED)).isEqualTo(Decision.SKIP);
    assertThat(g.preflight(USER, RefreshKind.ON_DEMAND)).isEqualTo(Decision.ALLOW);
  }

  // ---- just under the approached threshold → still ALLOW (boundary -1 mutation kill) ----

  @Test
  void justUnderApproachThreshold_allowsScheduled() {
    PriceFreshnessGuardrails g =
        new PriceFreshnessGuardrails(
            providerOf(trackingReturning(39L)), providerOf(propertiesWith(budget(true, 50L, 24))));

    assertThat(g.preflight(USER, RefreshKind.SCHEDULED)).isEqualTo(Decision.ALLOW);
  }

  // ---- cap reached → SKIP scheduled, ALLOW on-demand ----

  @Test
  void capReached_skipsScheduled_allowsOnDemand() {
    PriceFreshnessGuardrails g =
        new PriceFreshnessGuardrails(
            providerOf(trackingReturning(50L)), providerOf(propertiesWith(budget(true, 50L, 24))));

    assertThat(g.preflight(USER, RefreshKind.SCHEDULED)).isEqualTo(Decision.SKIP);
    // On-demand goes through; the on-demand provider call will trip AiUnavailableException
    // on its own when the AI guard rejects the next dispatch.
    assertThat(g.preflight(USER, RefreshKind.ON_DEMAND)).isEqualTo(Decision.ALLOW);
  }

  // ---- non-positive limit (monthly-cap proxy) → BLOCK everywhere ----

  @Test
  void zeroDailyLimit_blocksBoth() {
    PriceFreshnessGuardrails g =
        new PriceFreshnessGuardrails(
            providerOf(trackingReturning(0L)), providerOf(propertiesWith(budget(true, 0L, 24))));

    assertThat(g.preflight(USER, RefreshKind.SCHEDULED)).isEqualTo(Decision.BLOCK);
    assertThat(g.preflight(USER, RefreshKind.ON_DEMAND)).isEqualTo(Decision.BLOCK);
  }

  // ---- budget feature disabled → ALLOW everywhere (dev / test convenience) ----

  @Test
  void budgetDisabled_allowsBoth() {
    PriceFreshnessGuardrails g =
        new PriceFreshnessGuardrails(
            providerOf(trackingReturning(0L)), providerOf(propertiesWith(budget(false, 50L, 24))));

    assertThat(g.preflight(USER, RefreshKind.SCHEDULED)).isEqualTo(Decision.ALLOW);
    assertThat(g.preflight(USER, RefreshKind.ON_DEMAND)).isEqualTo(Decision.ALLOW);
  }

  // ---- degraded fallback: missing AiCostTrackingService → conservative ----

  @Test
  void missingCostTrackingSurface_degradesConservatively() {
    PriceFreshnessGuardrails g =
        new PriceFreshnessGuardrails(
            new EmptyObjectProvider<>(), providerOf(propertiesWith(budget(true, 50L, 24))));

    assertThat(g.preflight(USER, RefreshKind.SCHEDULED)).isEqualTo(Decision.SKIP);
    assertThat(g.preflight(USER, RefreshKind.ON_DEMAND)).isEqualTo(Decision.ALLOW);
  }

  // ---- degraded fallback: missing AiProperties → conservative ----

  @Test
  void missingAiPropertiesSurface_degradesConservatively() {
    PriceFreshnessGuardrails g =
        new PriceFreshnessGuardrails(
            providerOf(trackingReturning(0L)), new EmptyObjectProvider<>());

    assertThat(g.preflight(USER, RefreshKind.SCHEDULED)).isEqualTo(Decision.SKIP);
    assertThat(g.preflight(USER, RefreshKind.ON_DEMAND)).isEqualTo(Decision.ALLOW);
  }

  // ---- tracking read throws → conservative fallback ----

  @Test
  void trackingReadThrows_degradesConservatively() {
    AiCostTrackingService throwing = mock(AiCostTrackingService.class);
    when(throwing.pencesSpentBy(eq(USER), any(Duration.class)))
        .thenThrow(new RuntimeException("db down"));

    PriceFreshnessGuardrails g =
        new PriceFreshnessGuardrails(
            providerOf(throwing), providerOf(propertiesWith(budget(true, 50L, 24))));

    assertThat(g.preflight(USER, RefreshKind.SCHEDULED)).isEqualTo(Decision.SKIP);
    assertThat(g.preflight(USER, RefreshKind.ON_DEMAND)).isEqualTo(Decision.ALLOW);
  }

  // ---- null tracking result → defensive ALLOW (don't block on null) ----

  @Test
  void nullSpentResult_allows() {
    AiCostTrackingService nulling = mock(AiCostTrackingService.class);
    when(nulling.pencesSpentBy(eq(USER), any(Duration.class))).thenReturn(null);

    PriceFreshnessGuardrails g =
        new PriceFreshnessGuardrails(
            providerOf(nulling), providerOf(propertiesWith(budget(true, 50L, 24))));

    assertThat(g.preflight(USER, RefreshKind.SCHEDULED)).isEqualTo(Decision.ALLOW);
  }

  // ---- approachedThreshold boundary maths ----

  @Test
  void approachedThreshold_isFloorOf80Percent() {
    assertThat(PriceFreshnessGuardrails.approachedThreshold(50L)).isEqualTo(40L);
    assertThat(PriceFreshnessGuardrails.approachedThreshold(100L)).isEqualTo(80L);
    assertThat(PriceFreshnessGuardrails.approachedThreshold(7L)).isEqualTo(5L); // floor(7*0.8)=5
    assertThat(PriceFreshnessGuardrails.approachedThreshold(1L)).isZero();
  }
}
