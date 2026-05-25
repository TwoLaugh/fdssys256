package com.example.mealprep.planner.domain.service.internal.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.discovery.api.dto.DiscoveryJobDto;
import com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.service.DiscoveryService;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.config.PlannerProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit coverage for {@link ColdStartGate} — the three gate branches the brief calls out
 * (below-threshold fires; above-threshold skips; empty-discovery degrades) plus the request shape
 * it hands to discovery. No Spring context: {@link DiscoveryService} is mocked.
 */
class ColdStartGateTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID TRACE = UUID.randomUUID();

  private final DiscoveryService discoveryService = Mockito.mock(DiscoveryService.class);

  private ColdStartGate gate(PlannerProperties.ColdStart cfg) {
    return new ColdStartGate(discoveryService, propertiesWith(cfg));
  }

  private static PlannerProperties propertiesWith(PlannerProperties.ColdStart cfg) {
    // Only coldStart() is read by the gate; the rest are filler defaults.
    return new PlannerProperties(
        java.time.DayOfWeek.MONDAY,
        20,
        5,
        3,
        50,
        new java.math.BigDecimal("1.5"),
        Duration.ofSeconds(30),
        null,
        null,
        Duration.ofSeconds(20),
        3,
        5,
        2,
        null,
        null,
        cfg);
  }

  private static PlannerProperties.ColdStart cfg(
      boolean enabled, int multiplier, List<String> sourceKeys) {
    return new PlannerProperties.ColdStart(
        enabled, multiplier, 50, Duration.ofSeconds(20), sourceKeys);
  }

  private static List<MealSlotSkeleton> skeletons(SlotKind... kinds) {
    List<MealSlotSkeleton> out = new ArrayList<>();
    int idx = 0;
    for (SlotKind kind : kinds) {
      out.add(
          new MealSlotSkeleton(
              UUID.randomUUID(),
              UUID.randomUUID(),
              idx++,
              java.time.LocalDate.now(),
              kind,
              kind.name(),
              60,
              true,
              List.of(USER)));
    }
    return out;
  }

  private DiscoveryJobDto jobDto(DiscoveryJobStatus status, int ingested) {
    return new DiscoveryJobDto(
        UUID.randomUUID(),
        USER,
        DiscoveryJobTrigger.COLD_START,
        50,
        null,
        List.of(),
        status,
        null,
        null,
        null,
        0,
        0,
        ingested,
        0,
        List.of(),
        List.of(),
        null,
        TRACE,
        0L);
  }

  @Test
  void belowThreshold_fires_andRequestsDiscovery() {
    when(discoveryService.runJobSync(any(), any(), any()))
        .thenReturn(jobDto(DiscoveryJobStatus.SUCCEEDED, 18));
    // 1 distinct kind × multiplier 3 = threshold 3; pool 0 < 3 ⇒ fires.
    ColdStartGate gate = gate(cfg(true, 3, List.of()));

    boolean fired = gate.fillIfCold(USER, skeletons(SlotKind.DINNER), 0, TRACE);

    assertThat(fired).isTrue();
    ArgumentCaptor<StartDiscoveryJobRequest> req =
        ArgumentCaptor.forClass(StartDiscoveryJobRequest.class);
    verify(discoveryService).runJobSync(eq(USER), req.capture(), eq(Duration.ofSeconds(20)));
    assertThat(req.getValue().trigger()).isEqualTo(DiscoveryJobTrigger.COLD_START);
    assertThat(req.getValue().requestedCount()).isEqualTo(50);
    // Empty source-keys ⇒ null (all enabled sources).
    assertThat(req.getValue().sourceKeys()).isNull();
    assertThat(req.getValue().traceId()).isEqualTo(TRACE);
  }

  @Test
  void belowThreshold_withConfiguredSourceKeys_pinsThoseKeys() {
    when(discoveryService.runJobSync(any(), any(), any()))
        .thenReturn(jobDto(DiscoveryJobStatus.SUCCEEDED, 18));
    ColdStartGate gate = gate(cfg(true, 3, List.of("e2e_curated_seed")));

    gate.fillIfCold(USER, skeletons(SlotKind.DINNER), 0, TRACE);

    ArgumentCaptor<StartDiscoveryJobRequest> req =
        ArgumentCaptor.forClass(StartDiscoveryJobRequest.class);
    verify(discoveryService).runJobSync(eq(USER), req.capture(), any());
    assertThat(req.getValue().sourceKeys()).containsExactly("e2e_curated_seed");
  }

  @Test
  void atOrAboveThreshold_skips_noDiscoveryCall() {
    // 2 distinct kinds × multiplier 3 = threshold 6; pool 6 ⇒ NOT cold.
    ColdStartGate gate = gate(cfg(true, 3, List.of()));

    boolean fired = gate.fillIfCold(USER, skeletons(SlotKind.DINNER, SlotKind.LUNCH), 6, TRACE);

    assertThat(fired).isFalse();
    verifyNoInteractions(discoveryService);
  }

  @Test
  void disabled_skips_noDiscoveryCall() {
    ColdStartGate gate = gate(cfg(false, 3, List.of()));

    boolean fired = gate.fillIfCold(USER, skeletons(SlotKind.DINNER), 0, TRACE);

    assertThat(fired).isFalse();
    verifyNoInteractions(discoveryService);
  }

  @Test
  void emptyDiscovery_stillReportsColdStartFired_degradesGracefully() {
    // Discovery ran but imported nothing (FAILED / 0 ingested) — the gate still reports the
    // attempt (coldStart=true); the composer re-reads the still-empty pool and falls back.
    when(discoveryService.runJobSync(any(), any(), any()))
        .thenReturn(jobDto(DiscoveryJobStatus.FAILED, 0));
    ColdStartGate gate = gate(cfg(true, 3, List.of()));

    boolean fired = gate.fillIfCold(USER, skeletons(SlotKind.DINNER), 0, TRACE);

    assertThat(fired).isTrue();
    verify(discoveryService).runJobSync(any(), any(), any());
  }

  @Test
  void discoveryThrows_isSwallowed_andStillReportsColdStartFired() {
    when(discoveryService.runJobSync(any(), any(), any()))
        .thenThrow(new RuntimeException("discovery boom"));
    ColdStartGate gate = gate(cfg(true, 3, List.of()));

    boolean fired = gate.fillIfCold(USER, skeletons(SlotKind.DINNER), 0, TRACE);

    // Never propagates — the composer must not 500 on a discovery failure.
    assertThat(fired).isTrue();
  }

  @Test
  void noSkeletons_thresholdFloorsToMultiplier() {
    when(discoveryService.runJobSync(any(), any(), any()))
        .thenReturn(jobDto(DiscoveryJobStatus.SUCCEEDED, 5));
    // 0 distinct kinds → floor 1 × multiplier 3 = threshold 3; pool 2 < 3 ⇒ fires.
    ColdStartGate gate = gate(cfg(true, 3, List.of()));

    boolean fired = gate.fillIfCold(USER, List.of(), 2, TRACE);

    assertThat(fired).isTrue();
    verify(discoveryService).runJobSync(any(), any(), any());
  }

  @Test
  void aboveThresholdWithFloor_skips() {
    ColdStartGate gate = gate(cfg(true, 3, List.of()));
    boolean fired = gate.fillIfCold(USER, List.of(), 3, TRACE);
    assertThat(fired).isFalse();
    verify(discoveryService, never()).runJobSync(any(), any(), any());
  }
}
