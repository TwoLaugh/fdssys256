package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.grocery.api.dto.RecalculateShoppingListRequest;
import com.example.mealprep.grocery.api.dto.ShoppingListDto;
import com.example.mealprep.grocery.api.mapper.PriceObservationMapper;
import com.example.mealprep.grocery.api.mapper.ShoppingListMapper;
import com.example.mealprep.grocery.config.GroceryConfig;
import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.domain.entity.ShoppingListLineType;
import com.example.mealprep.grocery.event.ShoppingListGeneratedEvent;
import com.example.mealprep.grocery.exception.ShoppingListNotFoundException;
import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit test of {@code GroceryServiceImpl.recalculate} (grocery-01b) — idempotency on {@code
 * (planId, planGeneration)}, supersede-on-new-generation, the concurrent-insert race re-fetch, the
 * after-commit event, and the no-plan 404. Mock collaborators; no DB.
 */
@ExtendWith(MockitoExtension.class)
class ShoppingListServiceImplTest {

  private static final Instant NOW = Instant.parse("2026-05-27T12:00:00Z");
  private static final UUID USER = UUID.randomUUID();
  private static final UUID PLAN = UUID.randomUUID();

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private PriceDataGateway priceDataGateway;
  @Mock private PriceAggregator priceAggregator;
  @Mock private PriceObservationWriter priceObservationWriter;

  @Mock
  private com.example.mealprep.grocery.domain.service.ReferencePriceSource referencePriceSource;

  @Mock private PriceObservationMapper priceObservationMapper;
  @Mock private ShoppingListDataGateway shoppingListDataGateway;
  @Mock private ShoppingListCalculator shoppingListCalculator;
  @Mock private ShoppingListExporter shoppingListExporter;
  @Mock private ShoppingListMapper shoppingListMapper;
  @Mock private PlanQueryService planQueryService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private MarkBoughtInventoryBridge markBoughtInventoryBridge;

  private GroceryServiceImpl service;

  @BeforeEach
  void setUp() {
    GroceryConfig config =
        new GroceryConfig(
            new GroceryConfig.AggregatorConfig(90, 2.0, 90),
            new GroceryConfig.ConfidenceWeightsConfig(1.0, 0.85, 0.7, 0.4, 0.15),
            new GroceryConfig.InflationConfig(0.005),
            new GroceryConfig.FreshnessConfig(8, 50),
            new GroceryConfig.SchedulerConfig("0 0 4 * * SUN", "0 0 * * * *", "0 0 5 * * *"),
            new GroceryConfig.OrderConfig(24));
    service =
        new GroceryServiceImpl(
            priceDataGateway,
            priceAggregator,
            priceObservationWriter,
            referencePriceSource,
            priceObservationMapper,
            config,
            clock,
            shoppingListDataGateway,
            shoppingListCalculator,
            shoppingListExporter,
            shoppingListMapper,
            planQueryService,
            eventPublisher,
            markBoughtInventoryBridge,
            new com.example.mealprep.grocery.domain.service.internal.testsupport
                .EmptyObjectProvider<>(),
            new com.example.mealprep.grocery.domain.service.internal.testsupport
                .EmptyObjectProvider<>(),
            new com.example.mealprep.grocery.domain.service.internal.testsupport
                .EmptyObjectProvider<>(),
            new com.example.mealprep.grocery.domain.service.internal.testsupport
                .EmptyObjectProvider<>());
    lenient().when(shoppingListMapper.toDto(any())).thenReturn(dto());
    lenient()
        .when(shoppingListDataGateway.findWithLinesById(any()))
        .thenAnswer(inv -> Optional.empty());
  }

  @Test
  void recalculate_noPlan_throwsNotFound() {
    when(planQueryService.getPlanById(PLAN)).thenReturn(Optional.empty());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.recalculate(USER, new RecalculateShoppingListRequest(PLAN, null)))
        .isInstanceOf(ShoppingListNotFoundException.class);

    verify(shoppingListCalculator, never()).calculate(any(), any(), anyInt());
  }

  @Test
  void recalculate_sameGenerationExists_isIdempotent_noNewRowNoEvent() {
    when(planQueryService.getPlanById(PLAN)).thenReturn(Optional.of(plan(3)));
    ShoppingList existing = listEntity(3);
    when(shoppingListDataGateway.findByPlanIdAndPlanGeneration(PLAN, 3))
        .thenReturn(Optional.of(existing));
    when(shoppingListDataGateway.findWithLinesById(existing.getId()))
        .thenReturn(Optional.of(existing));

    service.recalculate(USER, new RecalculateShoppingListRequest(PLAN, 3));

    verify(shoppingListCalculator, never()).calculate(any(), any(), anyInt());
    verify(shoppingListDataGateway, never()).saveAndFlush(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void recalculate_newGeneration_createsList_supersedesPrior_publishesEvent() {
    PlanDto plan = plan(4);
    when(planQueryService.getPlanById(PLAN)).thenReturn(Optional.of(plan));
    when(shoppingListDataGateway.findByPlanIdAndPlanGeneration(PLAN, 4))
        .thenReturn(Optional.empty());
    ShoppingList prior = listEntity(3);
    when(shoppingListDataGateway.findActiveByPlanId(PLAN)).thenReturn(Optional.of(prior));
    ShoppingList fresh = listEntity(4);
    when(shoppingListCalculator.calculate(USER, plan, 4)).thenReturn(fresh);
    when(shoppingListDataGateway.saveAndFlush(fresh)).thenReturn(fresh);

    service.recalculate(USER, new RecalculateShoppingListRequest(PLAN, 4));

    // prior superseded
    assertThat(prior.getSupersededAt()).isEqualTo(NOW);
    // saved + event published once
    verify(shoppingListDataGateway, times(1)).saveAndFlush(fresh);
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(1)).publishEvent(captor.capture());
    assertThat(captor.getValue()).isInstanceOf(ShoppingListGeneratedEvent.class);
    ShoppingListGeneratedEvent ev = (ShoppingListGeneratedEvent) captor.getValue();
    assertThat(ev.planId()).isEqualTo(PLAN);
    assertThat(ev.planGeneration()).isEqualTo(4);
  }

  @Test
  void recalculate_concurrentInsertRace_catchesAndRefetches() {
    PlanDto plan = plan(5);
    when(planQueryService.getPlanById(PLAN)).thenReturn(Optional.of(plan));
    // First lookup: absent (we proceed to insert); the insert loses the UNIQUE race.
    ShoppingList winner = listEntity(5);
    when(shoppingListDataGateway.findByPlanIdAndPlanGeneration(PLAN, 5))
        .thenReturn(Optional.empty()) // pre-insert check
        .thenReturn(Optional.of(winner)); // post-race re-fetch
    when(shoppingListDataGateway.findActiveByPlanId(PLAN)).thenReturn(Optional.empty());
    when(shoppingListCalculator.calculate(USER, plan, 5)).thenReturn(listEntity(5));
    when(shoppingListDataGateway.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("unique (plan_id, plan_generation)"));
    when(shoppingListDataGateway.findWithLinesById(winner.getId())).thenReturn(Optional.of(winner));

    ShoppingListDto result = service.recalculate(USER, new RecalculateShoppingListRequest(PLAN, 5));

    assertThat(result).isNotNull();
    verify(shoppingListDataGateway, times(2)).findByPlanIdAndPlanGeneration(PLAN, 5);
    verify(eventPublisher, never()).publishEvent(any()); // the loser never publishes
  }

  @Test
  void recalculate_nullGeneration_usesPlanGeneration() {
    PlanDto plan = plan(7);
    when(planQueryService.getPlanById(PLAN)).thenReturn(Optional.of(plan));
    when(shoppingListDataGateway.findByPlanIdAndPlanGeneration(eq(PLAN), eq(7)))
        .thenReturn(Optional.empty());
    when(shoppingListDataGateway.findActiveByPlanId(PLAN)).thenReturn(Optional.empty());
    ShoppingList fresh = listEntity(7);
    when(shoppingListCalculator.calculate(USER, plan, 7)).thenReturn(fresh);
    when(shoppingListDataGateway.saveAndFlush(fresh)).thenReturn(fresh);

    service.recalculate(USER, new RecalculateShoppingListRequest(PLAN, null));

    verify(shoppingListCalculator).calculate(USER, plan, 7);
  }

  @Test
  void recalculate_forceTrue_rebuildsInPlace_preservingBoughtMarks_andPickingUpDrift() {
    // frontend-gaps: grocery-recalculate-pantry-drift — the full carry-rule matrix in one pass.
    // Existing generation-3 list: a BOUGHT flour line, an UNFILLED sugar line, a BOUGHT rice line.
    PlanDto plan = plan(3);
    ShoppingList existing = listEntity(3);
    ShoppingListLine flour =
        line("flour", ShoppingListLineType.PLANNED_DEMAND, LineFulfilmentStatus.BOUGHT, "500");
    flour.setBoughtQuantity(new BigDecimal("500"));
    flour.setBoughtPricePence(120);
    flour.setGroceryOrderId(UUID.randomUUID());
    ShoppingListLine sugar =
        line("sugar", ShoppingListLineType.PLANNED_DEMAND, LineFulfilmentStatus.UNFILLED, "200");
    ShoppingListLine rice =
        line("rice", ShoppingListLineType.PLANNED_DEMAND, LineFulfilmentStatus.BOUGHT, "1000");
    existing.getLines().add(flour);
    existing.getLines().add(sugar);
    existing.getLines().add(rice);

    // Fresh demand after pantry drift: flour now needs 750 (rose); butter is brand-new demand;
    // sugar + rice no longer have any demand.
    ShoppingList fresh = listEntity(3);
    fresh
        .getLines()
        .add(
            line(
                "flour",
                ShoppingListLineType.PLANNED_DEMAND,
                LineFulfilmentStatus.UNFILLED,
                "750"));
    fresh
        .getLines()
        .add(
            line(
                "butter",
                ShoppingListLineType.PLANNED_DEMAND,
                LineFulfilmentStatus.UNFILLED,
                "250"));
    fresh.setEstimatedTotalPence(999);

    when(planQueryService.getPlanById(PLAN)).thenReturn(Optional.of(plan));
    when(shoppingListDataGateway.findByPlanIdAndPlanGeneration(PLAN, 3))
        .thenReturn(Optional.of(existing));
    when(shoppingListDataGateway.findWithLinesById(existing.getId()))
        .thenReturn(Optional.of(existing));
    when(shoppingListCalculator.calculate(USER, plan, 3)).thenReturn(fresh);
    when(shoppingListDataGateway.saveAndFlush(existing)).thenReturn(existing);

    service.recalculate(USER, new RecalculateShoppingListRequest(PLAN, 3, true));

    // flour: BOUGHT carried (qty/price/orderId untouched), demand refreshed 500 -> 750.
    ShoppingListLine flourAfter = byKey(existing, "flour");
    assertThat(flourAfter.getFulfilmentStatus()).isEqualTo(LineFulfilmentStatus.BOUGHT);
    assertThat(flourAfter.getBoughtQuantity()).isEqualByComparingTo("500");
    assertThat(flourAfter.getBoughtPricePence()).isEqualTo(120);
    assertThat(flourAfter.getRequestedQuantity()).isEqualByComparingTo("750");
    // rice: decided line with no new demand -> kept as a purchase record.
    assertThat(byKey(existing, "rice").getFulfilmentStatus())
        .isEqualTo(LineFulfilmentStatus.BOUGHT);
    // butter: new demand -> added UNFILLED. sugar: UNFILLED with no demand -> dropped.
    assertThat(byKey(existing, "butter").getFulfilmentStatus())
        .isEqualTo(LineFulfilmentStatus.UNFILLED);
    assertThat(existing.getLines())
        .extracting(ShoppingListLine::getIngredientMappingKey)
        .containsExactlyInAnyOrder("flour", "rice", "butter");
    // Row identity preserved (same list id, no supersede), snapshot + generatedAt refreshed.
    assertThat(existing.getSupersededAt()).isNull();
    assertThat(existing.getEstimatedTotalPence()).isEqualTo(999);
    assertThat(existing.getGeneratedAt()).isEqualTo(NOW);
    verify(shoppingListDataGateway, times(1)).saveAndFlush(existing);
    verify(eventPublisher, times(1)).publishEvent(any(ShoppingListGeneratedEvent.class));
  }

  // ---- fixtures ----

  private static PlanDto plan(int generation) {
    return new PlanDto(
        PLAN,
        UUID.randomUUID(),
        LocalDate.of(2026, 6, 1),
        generation,
        null,
        PlanStatus.GENERATED,
        TriggerKind.USER_INITIATED,
        null,
        false,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        0L,
        NOW,
        NOW);
  }

  private static ShoppingList listEntity(int generation) {
    return ShoppingList.builder()
        .id(UUID.randomUUID())
        .userId(USER)
        .planId(PLAN)
        .planGeneration(generation)
        .generatedAt(NOW)
        .estimatedTotalCurrency("GBP")
        .staleIngredientCount(0)
        .pantryTrackingEnabled(false)
        .version(0L)
        .lines(new java.util.ArrayList<>())
        .build();
  }

  private static ShoppingListLine line(
      String key, ShoppingListLineType type, LineFulfilmentStatus status, String requestedQty) {
    return ShoppingListLine.builder()
        .id(UUID.randomUUID())
        .ingredientMappingKey(key)
        .displayName(key)
        .lineType(type)
        .fulfilmentStatus(status)
        .requestedQuantity(new BigDecimal(requestedQty))
        .requestedUnit("g")
        .build();
  }

  private static ShoppingListLine byKey(ShoppingList list, String key) {
    return list.getLines().stream()
        .filter(l -> key.equals(l.getIngredientMappingKey()))
        .findFirst()
        .orElseThrow();
  }

  private static ShoppingListDto dto() {
    return new ShoppingListDto(
        UUID.randomUUID(),
        USER,
        null,
        PLAN,
        1,
        NOW,
        null,
        null,
        null,
        null,
        "GBP",
        null,
        0,
        false,
        null,
        List.of(),
        0L);
  }
}
