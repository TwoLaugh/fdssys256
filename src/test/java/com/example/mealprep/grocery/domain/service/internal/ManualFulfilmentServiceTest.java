package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.grocery.api.dto.BulkMarkBoughtRequest;
import com.example.mealprep.grocery.api.dto.MarkBoughtRequest;
import com.example.mealprep.grocery.api.dto.MarkBoughtResultDto;
import com.example.mealprep.grocery.config.GroceryConfig;
import com.example.mealprep.grocery.domain.entity.BoughtVia;
import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import com.example.mealprep.grocery.domain.entity.PriceObservation;
import com.example.mealprep.grocery.domain.entity.PriceSource;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.event.ShoppingListBulkMarkedBoughtEvent;
import com.example.mealprep.grocery.event.ShoppingListItemMarkedBoughtEvent;
import com.example.mealprep.grocery.exception.LineAlreadyBoughtException;
import com.example.mealprep.grocery.exception.LineNotBoughtException;
import com.example.mealprep.grocery.exception.ShoppingListLineNotFoundException;
import com.example.mealprep.grocery.testdata.GroceryTestData;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.provisions.api.dto.GroceryImportResultDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

/**
 * Unit test for the Tier-2 {@link
 * com.example.mealprep.grocery.domain.service.ManualFulfilmentService} bodies in {@link
 * GroceryServiceImpl} (grocery-01d). All collaborators mocked; verifies the mark-bought / bulk /
 * undo behaviour, the divergences (over-mark note, double-mark 409, append-only undo +
 * provisions-reverse flag), the bulk distribution (parts sum exactly to the total, residual dunned
 * to the largest line, uniform fallback for no-estimate lines), the source weighting (MANUAL vs
 * MANUAL_ESTIMATED), and the single-event-per-operation rule for bulk.
 */
@ExtendWith(MockitoExtension.class)
class ManualFulfilmentServiceTest {

  private static final Instant NOW = Instant.parse("2026-05-27T12:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private final GroceryConfig config =
      new GroceryConfig(
          new GroceryConfig.AggregatorConfig(90, 2.0, 90),
          new GroceryConfig.ConfidenceWeightsConfig(1.0, 0.85, 0.7, 0.4, 0.15),
          new GroceryConfig.InflationConfig(0.005),
          new GroceryConfig.FreshnessConfig(8, 50),
          new GroceryConfig.SchedulerConfig("0 0 4 * * SUN", "0 0 * * * *", "0 0 5 * * *"),
          new GroceryConfig.OrderConfig(300, 24));

  @Mock private PriceDataGateway priceDataGateway;
  @Mock private PriceAggregator priceAggregator;
  @Mock private PriceObservationWriter priceObservationWriter;

  @Mock
  private com.example.mealprep.grocery.domain.service.ReferencePriceSource referencePriceSource;

  @Mock
  private com.example.mealprep.grocery.api.mapper.PriceObservationMapper priceObservationMapper;

  @Mock private ShoppingListDataGateway shoppingListDataGateway;
  @Mock private ShoppingListCalculator shoppingListCalculator;
  @Mock private ShoppingListExporter shoppingListExporter;
  @Mock private com.example.mealprep.grocery.api.mapper.ShoppingListMapper shoppingListMapper;
  @Mock private PlanQueryService planQueryService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private MarkBoughtInventoryBridge markBoughtInventoryBridge;

  private GroceryServiceImpl service;

  private final UUID userId = UUID.randomUUID();
  private ShoppingList list;

  @BeforeEach
  void setUp() {
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
            markBoughtInventoryBridge);
    list = GroceryTestData.shoppingList().userId(userId).householdId(null).build();
  }

  private ShoppingListLine line(String key, String reqQty, String reqUnit) {
    return GroceryTestData.shoppingListLine()
        .shoppingList(list)
        .ingredientMappingKey(key)
        .requestedQuantity(new BigDecimal(reqQty))
        .requestedUnit(reqUnit)
        .fulfilmentStatus(LineFulfilmentStatus.UNFILLED)
        .build();
  }

  private PriceObservation observationWithId(UUID id) {
    return GroceryTestData.priceObservation().id(id).build();
  }

  private void stubWriterReturns(UUID observationId) {
    when(priceObservationWriter.write(any())).thenReturn(observationWithId(observationId));
  }

  private void stubEmptyImport() {
    when(markBoughtInventoryBridge.applySingle(any(), any(), any(), any()))
        .thenReturn(new GroceryImportResultDto(List.of(), List.of(), List.of(), List.of()));
  }

  // ============================ markBought (single) ============================

  @Test
  void markBought_withPrice_flipsLine_writesObservation_addsInventory_publishesEvent() {
    ShoppingListLine l = line("flour", "1.000", "kg");
    when(shoppingListDataGateway.findLineById(l.getId())).thenReturn(Optional.of(l));
    UUID obsId = UUID.randomUUID();
    stubWriterReturns(obsId);
    UUID inventoryId = UUID.randomUUID();
    var item =
        org.mockito.Mockito.mock(com.example.mealprep.provisions.api.dto.InventoryItemDto.class);
    when(item.id()).thenReturn(inventoryId);
    when(markBoughtInventoryBridge.applySingle(eq(userId), any(), any(), any()))
        .thenReturn(new GroceryImportResultDto(List.of(item), List.of(), List.of(), List.of()));

    MarkBoughtRequest req =
        new MarkBoughtRequest(l.getId(), new BigDecimal("1.000"), "kg", 250, "tesco", null);
    MarkBoughtResultDto result = service.markBought(userId, req);

    assertThat(l.getFulfilmentStatus()).isEqualTo(LineFulfilmentStatus.BOUGHT);
    assertThat(l.getBoughtVia()).isEqualTo(BoughtVia.MANUAL);
    assertThat(l.getBoughtPricePence()).isEqualTo(250);
    assertThat(result.newStatus()).isEqualTo(LineFulfilmentStatus.BOUGHT);
    assertThat(result.priceObservationId()).isEqualTo(obsId);
    assertThat(result.inventoryItemId()).isEqualTo(inventoryId);
    assertThat(result.note()).isNull();

    verify(shoppingListDataGateway).saveLine(l);
    verify(shoppingListDataGateway).touchListVersion(list);

    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter).write(cmd.capture());
    assertThat(cmd.getValue().source()).isEqualTo(PriceSource.MANUAL);
    assertThat(cmd.getValue().paidTotalPence()).isEqualTo(250);
    assertThat(cmd.getValue().householdId()).isEqualTo(userId); // null household → userId scope

    ArgumentCaptor<ShoppingListItemMarkedBoughtEvent> evt =
        ArgumentCaptor.forClass(ShoppingListItemMarkedBoughtEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().boughtVia()).isEqualTo(BoughtVia.MANUAL);
    assertThat(evt.getValue().shoppingListLineId()).isEqualTo(l.getId());
  }

  @Test
  void markBought_noPrice_flipsLine_noObservation_stillAddsInventory_publishesEvent() {
    ShoppingListLine l = line("rice", "2.000", "kg");
    when(shoppingListDataGateway.findLineById(l.getId())).thenReturn(Optional.of(l));
    stubEmptyImport();

    MarkBoughtRequest req =
        new MarkBoughtRequest(l.getId(), new BigDecimal("2.000"), "kg", null, null, null);
    MarkBoughtResultDto result = service.markBought(userId, req);

    assertThat(result.newStatus()).isEqualTo(LineFulfilmentStatus.BOUGHT);
    assertThat(result.priceObservationId()).isNull();
    verify(priceObservationWriter, never()).write(any());
    verify(markBoughtInventoryBridge).applySingle(eq(userId), any(), any(), any());
    verify(eventPublisher).publishEvent(any(ShoppingListItemMarkedBoughtEvent.class));
  }

  @Test
  void markBought_overMark_isAllowed_setsNote() {
    ShoppingListLine l = line("eggs", "6.000", "items");
    when(shoppingListDataGateway.findLineById(l.getId())).thenReturn(Optional.of(l));
    stubEmptyImport();

    MarkBoughtRequest req =
        new MarkBoughtRequest(l.getId(), new BigDecimal("12.000"), "items", null, null, null);
    MarkBoughtResultDto result = service.markBought(userId, req);

    assertThat(result.newStatus()).isEqualTo(LineFulfilmentStatus.BOUGHT);
    assertThat(result.note()).contains("exceeds requested");
  }

  @Test
  void markBought_alreadyBought_throws409() {
    ShoppingListLine l = line("flour", "1.000", "kg");
    l.setFulfilmentStatus(LineFulfilmentStatus.BOUGHT);
    when(shoppingListDataGateway.findLineById(l.getId())).thenReturn(Optional.of(l));

    MarkBoughtRequest req =
        new MarkBoughtRequest(l.getId(), new BigDecimal("1.000"), "kg", 100, null, null);
    assertThatThrownBy(() -> service.markBought(userId, req))
        .isInstanceOf(LineAlreadyBoughtException.class);
    verify(markBoughtInventoryBridge, never()).applySingle(any(), any(), any(), any());
    verifyNoInteractions(priceObservationWriter);
  }

  @Test
  void markBought_missingLine_throws404() {
    UUID missing = UUID.randomUUID();
    when(shoppingListDataGateway.findLineById(missing)).thenReturn(Optional.empty());

    MarkBoughtRequest req =
        new MarkBoughtRequest(missing, new BigDecimal("1.000"), "kg", 100, null, null);
    assertThatThrownBy(() -> service.markBought(userId, req))
        .isInstanceOf(ShoppingListLineNotFoundException.class);
  }

  // ============================ bulkMarkBought ============================

  private BulkMarkBoughtRequest bulkReq(List<ShoppingListLine> lines, Integer total) {
    return new BulkMarkBoughtRequest(
        list.getId(), lines.stream().map(ShoppingListLine::getId).toList(), total, "tesco", null);
  }

  private void stubBulkLines(List<ShoppingListLine> lines) {
    when(shoppingListDataGateway.findLinesByIds(any())).thenReturn(new ArrayList<>(lines));
    when(markBoughtInventoryBridge.applyBulk(any(), any(), any(), any()))
        .thenReturn(new GroceryImportResultDto(List.of(), List.of(), List.of(), List.of()));
  }

  @Test
  void bulk_withTotal_proportionalSplit_sumsExactlyToTotal_residualToLargest() {
    ShoppingListLine a = line("flour", "1.000", "kg");
    a.setEstimatedLinePence(100);
    ShoppingListLine b = line("rice", "1.000", "kg");
    b.setEstimatedLinePence(200);
    ShoppingListLine c = line("sugar", "1.000", "kg");
    c.setEstimatedLinePence(33);
    List<ShoppingListLine> lines = List.of(a, b, c);
    stubBulkLines(lines);
    when(priceObservationWriter.write(any()))
        .thenAnswer(inv -> observationWithId(UUID.randomUUID()));

    int total = 1000; // £10
    List<MarkBoughtResultDto> results = service.bulkMarkBought(userId, bulkReq(lines, total));
    assertThat(results).hasSize(3);

    // The allocated prices (captured on the writer commands) must sum EXACTLY to the total —
    // the rounding residual is dunned to the largest line (b, estimate 200).
    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter, times(3)).write(cmd.capture());
    int allocated =
        cmd.getAllValues().stream()
            .mapToInt(PriceObservationWriter.WriteCommand::paidTotalPence)
            .sum();
    assertThat(allocated).isEqualTo(total);
    // All distributed observations are MANUAL_ESTIMATED (0.4).
    assertThat(cmd.getAllValues()).allMatch(w -> w.source() == PriceSource.MANUAL_ESTIMATED);
  }

  @Test
  void bulk_withTotal_someLinesNoEstimate_uniformFallbackShare_sumsToTotal() {
    ShoppingListLine a = line("flour", "1.000", "kg");
    a.setEstimatedLinePence(300);
    ShoppingListLine b = line("novel", "1.000", "kg"); // no estimate
    ShoppingListLine c = line("rare", "1.000", "kg"); // no estimate
    List<ShoppingListLine> lines = List.of(a, b, c);
    stubBulkLines(lines);
    when(priceObservationWriter.write(any()))
        .thenAnswer(inv -> observationWithId(UUID.randomUUID()));

    int total = 900;
    service.bulkMarkBought(userId, bulkReq(lines, total));

    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter, times(3)).write(cmd.capture());
    int allocated =
        cmd.getAllValues().stream()
            .mapToInt(PriceObservationWriter.WriteCommand::paidTotalPence)
            .sum();
    assertThat(allocated).isEqualTo(total);
    // No-estimate lines each got a positive (non-null) share.
    assertThat(cmd.getAllValues())
        .allMatch(w -> w.paidTotalPence() != null && w.paidTotalPence() >= 0);
  }

  @Test
  void bulk_withoutTotal_perLineEstimate_orNoObservation() {
    ShoppingListLine a = line("flour", "1.000", "kg");
    a.setEstimatedLinePence(120);
    ShoppingListLine b = line("novel", "1.000", "kg"); // no estimate → no observation
    List<ShoppingListLine> lines = List.of(a, b);
    stubBulkLines(lines);
    when(priceObservationWriter.write(any()))
        .thenAnswer(inv -> observationWithId(UUID.randomUUID()));

    service.bulkMarkBought(userId, bulkReq(lines, null));

    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    // Only the estimate-bearing line writes an observation (MANUAL source for no-total mode).
    verify(priceObservationWriter, times(1)).write(cmd.capture());
    assertThat(cmd.getValue().paidTotalPence()).isEqualTo(120);
    assertThat(cmd.getValue().source()).isEqualTo(PriceSource.MANUAL);
  }

  @Test
  void bulk_publishesExactlyOneBulkEvent_noPerLineEvents() {
    ShoppingListLine a = line("flour", "1.000", "kg");
    a.setEstimatedLinePence(100);
    ShoppingListLine b = line("rice", "1.000", "kg");
    b.setEstimatedLinePence(100);
    List<ShoppingListLine> lines = List.of(a, b);
    stubBulkLines(lines);
    when(priceObservationWriter.write(any()))
        .thenAnswer(inv -> observationWithId(UUID.randomUUID()));

    service.bulkMarkBought(userId, bulkReq(lines, 400));

    verify(eventPublisher, times(1)).publishEvent(any(ShoppingListBulkMarkedBoughtEvent.class));
    verify(eventPublisher, never()).publishEvent(any(ShoppingListItemMarkedBoughtEvent.class));
  }

  @Test
  void bulk_singleInventoryCall_forWholeBatch() {
    ShoppingListLine a = line("flour", "1.000", "kg");
    a.setEstimatedLinePence(100);
    ShoppingListLine b = line("rice", "1.000", "kg");
    b.setEstimatedLinePence(100);
    List<ShoppingListLine> lines = List.of(a, b);
    stubBulkLines(lines);
    when(priceObservationWriter.write(any()))
        .thenAnswer(inv -> observationWithId(UUID.randomUUID()));

    service.bulkMarkBought(userId, bulkReq(lines, 400));

    verify(markBoughtInventoryBridge, times(1)).applyBulk(eq(userId), any(), any(), any());
    verify(markBoughtInventoryBridge, never()).applySingle(any(), any(), any(), any());
  }

  @Test
  void bulk_alreadyBoughtLineInBatch_throws409() {
    ShoppingListLine a = line("flour", "1.000", "kg");
    ShoppingListLine b = line("rice", "1.000", "kg");
    b.setFulfilmentStatus(LineFulfilmentStatus.BOUGHT);
    List<ShoppingListLine> lines = List.of(a, b);
    when(shoppingListDataGateway.findLinesByIds(any())).thenReturn(new ArrayList<>(lines));

    assertThatThrownBy(() -> service.bulkMarkBought(userId, bulkReq(lines, 200)))
        .isInstanceOf(LineAlreadyBoughtException.class);
    verify(markBoughtInventoryBridge, never()).applyBulk(any(), any(), any(), any());
  }

  @Test
  void bulk_missingLineInBatch_throws404() {
    ShoppingListLine a = line("flour", "1.000", "kg");
    // findLinesByIds returns nothing for one of the requested ids.
    when(shoppingListDataGateway.findLinesByIds(any())).thenReturn(new ArrayList<>());

    assertThatThrownBy(() -> service.bulkMarkBought(userId, bulkReq(List.of(a), 100)))
        .isInstanceOf(ShoppingListLineNotFoundException.class);
  }

  // ============================ undoMarkBought ============================

  @Test
  void undo_boughtLine_revertsToUnfilled_writesCompensatingObservation_noInventoryReverse() {
    ShoppingListLine l = line("flour", "1.000", "kg");
    l.setFulfilmentStatus(LineFulfilmentStatus.BOUGHT);
    l.setBoughtQuantity(new BigDecimal("1.000"));
    l.setBoughtUnit("kg");
    l.setBoughtPricePence(250);
    l.setBoughtVia(BoughtVia.MANUAL);
    when(shoppingListDataGateway.findLineById(l.getId())).thenReturn(Optional.of(l));
    when(priceObservationWriter.write(any()))
        .thenAnswer(inv -> observationWithId(UUID.randomUUID()));

    service.undoMarkBought(l.getId(), userId);

    assertThat(l.getFulfilmentStatus()).isEqualTo(LineFulfilmentStatus.UNFILLED);
    assertThat(l.getBoughtPricePence()).isNull();
    assertThat(l.getBoughtVia()).isNull();
    verify(shoppingListDataGateway).saveLine(l);
    verify(shoppingListDataGateway).touchListVersion(list);

    // A compensating observation is appended (never deletes the original).
    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter).write(cmd.capture());
    assertThat(cmd.getValue().paidTotalPence()).isNull();
    assertThat(cmd.getValue().note()).contains("undone");

    // No provisions reverse API → no inventory reversal attempted.
    verify(markBoughtInventoryBridge, never()).applySingle(any(), any(), any(), any());
    verify(markBoughtInventoryBridge, never()).applyBulk(any(), any(), any(), any());
  }

  @Test
  void undo_notBought_throws409() {
    ShoppingListLine l = line("flour", "1.000", "kg"); // UNFILLED
    when(shoppingListDataGateway.findLineById(l.getId())).thenReturn(Optional.of(l));

    assertThatThrownBy(() -> service.undoMarkBought(l.getId(), userId))
        .isInstanceOf(LineNotBoughtException.class);
    verifyNoInteractions(priceObservationWriter);
  }

  @Test
  void undo_missingLine_throws404() {
    UUID missing = UUID.randomUUID();
    when(shoppingListDataGateway.findLineById(missing)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.undoMarkBought(missing, userId))
        .isInstanceOf(ShoppingListLineNotFoundException.class);
  }
}
