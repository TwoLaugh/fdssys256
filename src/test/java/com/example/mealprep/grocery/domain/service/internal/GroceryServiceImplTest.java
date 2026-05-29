package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.grocery.api.dto.BulkMarkBoughtRequest;
import com.example.mealprep.grocery.api.dto.ExportFormat;
import com.example.mealprep.grocery.api.dto.MarkBoughtRequest;
import com.example.mealprep.grocery.api.dto.MarkBoughtResultDto;
import com.example.mealprep.grocery.api.dto.PriceAggregateDto;
import com.example.mealprep.grocery.api.dto.PriceObservationDto;
import com.example.mealprep.grocery.api.dto.RecordManualPriceRequest;
import com.example.mealprep.grocery.api.dto.RefreshPricesRequest;
import com.example.mealprep.grocery.api.dto.RefreshPricesResultDto;
import com.example.mealprep.grocery.api.dto.ShoppingListDto;
import com.example.mealprep.grocery.api.dto.ShoppingListExportDto;
import com.example.mealprep.grocery.api.mapper.PriceObservationMapper;
import com.example.mealprep.grocery.api.mapper.ShoppingListMapper;
import com.example.mealprep.grocery.config.GroceryConfig;
import com.example.mealprep.grocery.domain.entity.BoughtVia;
import com.example.mealprep.grocery.domain.entity.GroceryProviderState;
import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import com.example.mealprep.grocery.domain.entity.OrderLineStatus;
import com.example.mealprep.grocery.domain.entity.PriceObservation;
import com.example.mealprep.grocery.domain.entity.PriceSource;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource.ReferencePrice;
import com.example.mealprep.grocery.domain.service.internal.providers.BasketDraft;
import com.example.mealprep.grocery.domain.service.internal.providers.GroceryProvider;
import com.example.mealprep.grocery.domain.service.internal.providers.ProviderUnavailableException;
import com.example.mealprep.grocery.domain.service.internal.providers.QuoteLineResult;
import com.example.mealprep.grocery.domain.service.internal.providers.QuoteResult;
import com.example.mealprep.grocery.domain.service.internal.testsupport.EmptyObjectProvider;
import com.example.mealprep.grocery.testdata.GroceryTestData;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.provisions.api.dto.GroceryImportResultDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit test for the Tier-4 {@code PriceHistoryService} bodies and the grocery-01g scheduled-refresh
 * cluster in {@link GroceryServiceImpl}. The Tier-1 ({@code recalculate}) and Tier-2 ({@code
 * markBought} / bulk / undo) bodies are covered by {@link ShoppingListServiceImplTest} and {@link
 * ManualFulfilmentServiceTest}; this class fills the previously-mutation-excluded Tier-4 read /
 * write paths ({@code getAggregate}, {@code getAggregatesByKeys}, {@code
 * getCrossStoreAggregatesByKey}, {@code getObservations*}, {@code recordManualPrice}, {@code
 * refreshOnDemand}) and the entirely-new 01g worker ({@code runScheduledBackgroundRefresh} + {@code
 * resolveTopUsedKeys} + {@code quoteAndWriteObservations}). Pure unit test — all collaborators
 * mocked.
 */
@ExtendWith(MockitoExtension.class)
class GroceryServiceImplTest {

  private static final Instant NOW = Instant.parse("2026-05-29T12:00:00Z");
  private static final Instant WINDOW_START = Instant.parse("2026-02-28T12:00:00Z");
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
  @Mock private ReferencePriceSource referencePriceSource;
  @Mock private PriceObservationMapper priceObservationMapper;
  @Mock private ShoppingListDataGateway shoppingListDataGateway;
  @Mock private ShoppingListCalculator shoppingListCalculator;
  @Mock private ShoppingListExporter shoppingListExporter;
  @Mock private ShoppingListMapper shoppingListMapper;
  @Mock private PlanQueryService planQueryService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private MarkBoughtInventoryBridge markBoughtInventoryBridge;

  // 01g collaborators — wrapped in ObjectProvider. Defaults to "no bean available" (empty
  // providers); individual tests swap in a stubbed ObjectProvider where the path needs one.
  @Mock private GroceryProvider provider;
  @Mock private RecipeQueryService recipeQueryService;
  @Mock private PriceFreshnessGuardrails guardrails;
  @Mock private GroceryOrderDataGateway orderGateway;

  private final UUID userId = UUID.randomUUID();

  // ---- builders for the per-test service wiring ----

  private GroceryServiceImpl service(
      ObjectProvider<GroceryProvider> providers,
      ObjectProvider<RecipeQueryService> recipeProvider,
      ObjectProvider<PriceFreshnessGuardrails> guardrailsProvider,
      ObjectProvider<GroceryOrderDataGateway> orderGatewayProvider) {
    return new GroceryServiceImpl(
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
        providers,
        recipeProvider,
        guardrailsProvider,
        orderGatewayProvider);
  }

  /** The Tier-4-only wiring: all 01g providers empty (no scheduled-refresh collaborators). */
  private GroceryServiceImpl tier4Service() {
    return service(
        new EmptyObjectProvider<>(),
        new EmptyObjectProvider<>(),
        new EmptyObjectProvider<>(),
        new EmptyObjectProvider<>());
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<GroceryProvider> providersOf(GroceryProvider p) {
    ObjectProvider<GroceryProvider> op = org.mockito.Mockito.mock(ObjectProvider.class);
    lenient().when(op.orderedStream()).thenAnswer(inv -> java.util.stream.Stream.of(p));
    return op;
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectProvider<T> providerOf(T bean) {
    ObjectProvider<T> op = org.mockito.Mockito.mock(ObjectProvider.class);
    lenient().when(op.getIfAvailable()).thenReturn(bean);
    return op;
  }

  private PriceObservation obs(String key, String store, int unitPence) {
    return GroceryTestData.priceObservation()
        .ingredientMappingKey(key)
        .store(store)
        .paidUnitPence(unitPence)
        .build();
  }

  private PriceAggregateDto agg(String key, String store, int estimate) {
    return new PriceAggregateDto(
        key, store, estimate, new BigDecimal("0.500"), estimate, estimate, NOW, NOW, NOW, 1, false);
  }

  private ShoppingListDto listDto() {
    return new ShoppingListDto(
        UUID.randomUUID(),
        userId,
        null,
        UUID.randomUUID(),
        1,
        NOW,
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

  // ============================ Tier-1 reads (getById / getByIds / getCurrentByPlanId /
  // getHistory / export) ============================

  @Test
  void getById_present_mapsToDto() {
    UUID id = UUID.randomUUID();
    ShoppingList list = GroceryTestData.shoppingList().id(id).userId(userId).build();
    when(shoppingListDataGateway.findWithLinesById(id)).thenReturn(Optional.of(list));
    ShoppingListDto dto = listDto();
    when(shoppingListMapper.toDto(list)).thenReturn(dto);

    assertThat(tier4Service().getById(id)).contains(dto);
  }

  @Test
  void getById_missing_returnsEmpty_noMapping() {
    UUID id = UUID.randomUUID();
    when(shoppingListDataGateway.findWithLinesById(id)).thenReturn(Optional.empty());

    assertThat(tier4Service().getById(id)).isEmpty();
    verify(shoppingListMapper, never()).toDto(any());
  }

  @Test
  void getCurrentByPlanId_present_loadsWithLines_thenMaps() {
    UUID planId = UUID.randomUUID();
    ShoppingList active = GroceryTestData.shoppingList().userId(userId).build();
    when(shoppingListDataGateway.findActiveByPlanId(planId)).thenReturn(Optional.of(active));
    when(shoppingListDataGateway.findWithLinesById(active.getId())).thenReturn(Optional.of(active));
    ShoppingListDto dto = listDto();
    when(shoppingListMapper.toDto(active)).thenReturn(dto);

    assertThat(tier4Service().getCurrentByPlanId(planId)).contains(dto);
  }

  @Test
  void getCurrentByPlanId_noActive_returnsEmpty() {
    UUID planId = UUID.randomUUID();
    when(shoppingListDataGateway.findActiveByPlanId(planId)).thenReturn(Optional.empty());

    assertThat(tier4Service().getCurrentByPlanId(planId)).isEmpty();
    verify(shoppingListMapper, never()).toDto(any());
  }

  @Test
  void getByIds_nullOrEmpty_returnsEmptyList_noQuery() {
    assertThat(tier4Service().getByIds(null)).isEmpty();
    assertThat(tier4Service().getByIds(List.of())).isEmpty();
    verify(shoppingListDataGateway, never()).findWithLinesById(any());
  }

  @Test
  void getByIds_returnsOnlyFoundRows_inRequestOrder() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID(); // missing
    UUID c = UUID.randomUUID();
    ShoppingList la = GroceryTestData.shoppingList().id(a).build();
    ShoppingList lc = GroceryTestData.shoppingList().id(c).build();
    when(shoppingListDataGateway.findWithLinesById(a)).thenReturn(Optional.of(la));
    when(shoppingListDataGateway.findWithLinesById(b)).thenReturn(Optional.empty());
    when(shoppingListDataGateway.findWithLinesById(c)).thenReturn(Optional.of(lc));
    ShoppingListDto da = listDto();
    ShoppingListDto dc = listDto();
    when(shoppingListMapper.toDto(la)).thenReturn(da);
    when(shoppingListMapper.toDto(lc)).thenReturn(dc);

    List<ShoppingListDto> out = tier4Service().getByIds(List.of(a, b, c));

    // Missing id b is skipped; the two found rows are returned in request order.
    assertThat(out).containsExactly(da, dc);
  }

  @Test
  void getHistory_loadsEachWithLines_andMaps() {
    Pageable pageable = PageRequest.of(0, 5);
    ShoppingList row = GroceryTestData.shoppingList().userId(userId).build();
    when(shoppingListDataGateway.findHistoryByUserId(userId, pageable))
        .thenReturn(new PageImpl<>(List.of(row), pageable, 1));
    when(shoppingListDataGateway.findWithLinesById(row.getId())).thenReturn(Optional.of(row));
    ShoppingListDto dto = listDto();
    when(shoppingListMapper.toDto(row)).thenReturn(dto);

    Page<ShoppingListDto> out = tier4Service().getHistory(userId, pageable);

    assertThat(out.getContent()).containsExactly(dto);
  }

  @Test
  void export_present_rendersContent_intoExportDto() {
    UUID id = UUID.randomUUID();
    ShoppingList list = GroceryTestData.shoppingList().id(id).build();
    when(shoppingListDataGateway.findWithLinesById(id)).thenReturn(Optional.of(list));
    when(shoppingListExporter.render(list, ExportFormat.CSV)).thenReturn("a,b,c");

    ShoppingListExportDto out = tier4Service().export(id, ExportFormat.CSV);

    assertThat(out.shoppingListId()).isEqualTo(id);
    assertThat(out.format()).isEqualTo(ExportFormat.CSV);
    assertThat(out.content()).isEqualTo("a,b,c");
  }

  @Test
  void export_missing_throwsNotFound() {
    UUID id = UUID.randomUUID();
    when(shoppingListDataGateway.findWithLinesById(id)).thenReturn(Optional.empty());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> tier4Service().export(id, ExportFormat.CSV))
        .isInstanceOf(com.example.mealprep.grocery.exception.ShoppingListNotFoundException.class);
    verify(shoppingListExporter, never()).render(any(), any());
  }

  // ============================ bulk distribute — exact per-line allocation
  // (kills the distribute() arithmetic + boundary mutants) ============================

  private ShoppingList bulkParent() {
    return GroceryTestData.shoppingList().userId(userId).householdId(null).build();
  }

  private ShoppingListLine bulkLine(ShoppingList parent, String key, String reqQty, Integer est) {
    ShoppingListLine l =
        GroceryTestData.shoppingListLine()
            .shoppingList(parent)
            .ingredientMappingKey(key)
            .requestedQuantity(new BigDecimal(reqQty))
            .requestedUnit("kg")
            .fulfilmentStatus(LineFulfilmentStatus.UNFILLED)
            .build();
    l.setEstimatedLinePence(est);
    return l;
  }

  private BulkMarkBoughtRequest bulkReq(
      ShoppingList parent, List<ShoppingListLine> lines, Integer total) {
    return new BulkMarkBoughtRequest(
        parent.getId(), lines.stream().map(ShoppingListLine::getId).toList(), total, "tesco", null);
  }

  /** Capture the per-line allocated pence from the writer commands keyed by line id. */
  private Map<UUID, Integer> runBulkAndCaptureAllocation(
      ShoppingList parent, List<ShoppingListLine> lines, Integer total) {
    when(shoppingListDataGateway.findLinesByIds(any())).thenReturn(new ArrayList<>(lines));
    when(markBoughtInventoryBridge.applyBulk(any(), any(), any(), any()))
        .thenReturn(new GroceryImportResultDto(List.of(), List.of(), List.of(), List.of()));
    when(priceObservationWriter.write(any()))
        .thenAnswer(inv -> GroceryTestData.priceObservation().id(UUID.randomUUID()).build());

    tier4Service().bulkMarkBought(userId, bulkReq(parent, lines, total));

    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter, org.mockito.Mockito.atLeast(0)).write(cmd.capture());
    Map<UUID, Integer> byLine = new LinkedHashMap<>();
    for (PriceObservationWriter.WriteCommand w : cmd.getAllValues()) {
      byLine.put(w.shoppingListLineId(), w.paidTotalPence());
    }
    return byLine;
  }

  @Test
  void bulk_allAnchored_exactProportionalShares_residualToLargest() {
    ShoppingList parent = bulkParent();
    ShoppingListLine a = bulkLine(parent, "flour", "1", 100); // weight 100
    ShoppingListLine b = bulkLine(parent, "rice", "1", 300); // weight 300 (largest)
    List<ShoppingListLine> lines = List.of(a, b);

    Map<UUID, Integer> alloc = runBulkAndCaptureAllocation(parent, lines, 1000);

    // anchoredSum=400, no fallback. a = round(1000*100/400)=250; b = round(1000*300/400)=750.
    // allocated=1000, residual=0, dunned to b (largest) → b stays 750.
    assertThat(alloc.get(a.getId())).isEqualTo(250);
    assertThat(alloc.get(b.getId())).isEqualTo(750);
    assertThat(alloc.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(1000);
  }

  @Test
  void bulk_residual_dunnedToLargestAnchoredLine_exactly() {
    ShoppingList parent = bulkParent();
    // Weights that force a rounding residual: 1,1,1 over total 1000 → each round(1000/3)=333,
    // allocated 999, residual +1 dunned to the largest (first-seen tie → a).
    ShoppingListLine a = bulkLine(parent, "a", "1", 1);
    ShoppingListLine b = bulkLine(parent, "b", "1", 1);
    ShoppingListLine c = bulkLine(parent, "c", "1", 1);
    List<ShoppingListLine> lines = List.of(a, b, c);

    Map<UUID, Integer> alloc = runBulkAndCaptureAllocation(parent, lines, 1000);

    assertThat(alloc.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(1000);
    // a is the first line with the (tied) max weight → gets the +1 residual → 334; b,c → 333.
    assertThat(alloc.get(a.getId())).isEqualTo(334);
    assertThat(alloc.get(b.getId())).isEqualTo(333);
    assertThat(alloc.get(c.getId())).isEqualTo(333);
  }

  @Test
  void bulk_noAnchorsAtAll_pureUniformSplit_sumsExactly_residualSpreadByRunningTotal() {
    ShoppingList parent = bulkParent();
    // No line has an estimate → anchoredSum==0 → the pure-uniform branch (L632-640).
    ShoppingListLine a = bulkLine(parent, "a", "1", null);
    ShoppingListLine b = bulkLine(parent, "b", "1", null);
    ShoppingListLine c = bulkLine(parent, "c", "1", null);
    List<ShoppingListLine> lines = List.of(a, b, c);

    Map<UUID, Integer> alloc = runBulkAndCaptureAllocation(parent, lines, 100);

    // share_i = floor(100*(i+1)/3) - allocatedSoFar: 33, 33, 34 → sums to 100 exactly.
    assertThat(alloc.get(a.getId())).isEqualTo(33);
    assertThat(alloc.get(b.getId())).isEqualTo(33);
    assertThat(alloc.get(c.getId())).isEqualTo(34);
    assertThat(alloc.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
  }

  @Test
  void bulk_mixedAnchoredAndFallback_fallbackEachIsAverageShare() {
    ShoppingList parent = bulkParent();
    ShoppingListLine anchored = bulkLine(parent, "anchored", "1", 400);
    ShoppingListLine noEst1 = bulkLine(parent, "x", "1", null);
    ShoppingListLine noEst2 = bulkLine(parent, "y", "1", null);
    List<ShoppingListLine> lines = List.of(anchored, noEst1, noEst2);

    Map<UUID, Integer> alloc = runBulkAndCaptureAllocation(parent, lines, 900);

    // fallbackEach = round(900/3)=300 each for the 2 no-estimate lines = 600 pool;
    // anchoredBudget = 900-600 = 300; anchored share = round(300*400/400)=300; residual 0.
    assertThat(alloc.get(noEst1.getId())).isEqualTo(300);
    assertThat(alloc.get(noEst2.getId())).isEqualTo(300);
    assertThat(alloc.get(anchored.getId())).isEqualTo(300);
    assertThat(alloc.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(900);
  }

  // ============================ applyBought / undo — each bought_* field is set
  // (kills void-method-call-removal mutants) ============================

  @Test
  void markBought_setsEveryBoughtField_exactly() {
    ShoppingList parent = bulkParent();
    ShoppingListLine l = bulkLine(parent, "flour", "1.000", null);
    when(shoppingListDataGateway.findLineById(l.getId())).thenReturn(Optional.of(l));
    when(markBoughtInventoryBridge.applySingle(any(), any(), any(), any()))
        .thenReturn(new GroceryImportResultDto(List.of(), List.of(), List.of(), List.of()));
    when(priceObservationWriter.write(any()))
        .thenReturn(GroceryTestData.priceObservation().id(UUID.randomUUID()).build());

    Instant boughtAt = Instant.parse("2026-05-20T09:00:00Z");
    MarkBoughtRequest req =
        new MarkBoughtRequest(l.getId(), new BigDecimal("3.000"), "kg", 555, "tesco", boughtAt);
    MarkBoughtResultDto result = tier4Service().markBought(userId, req);

    // Every bought_* field carries the exact request value (no setter removed).
    assertThat(l.getFulfilmentStatus()).isEqualTo(LineFulfilmentStatus.BOUGHT);
    assertThat(l.getBoughtQuantity()).isEqualByComparingTo(new BigDecimal("3.000"));
    assertThat(l.getBoughtUnit()).isEqualTo("kg");
    assertThat(l.getBoughtPricePence()).isEqualTo(555);
    assertThat(l.getBoughtAt()).isEqualTo(boughtAt);
    assertThat(l.getBoughtVia()).isEqualTo(BoughtVia.MANUAL);
    assertThat(result.newStatus()).isEqualTo(LineFulfilmentStatus.BOUGHT);
  }

  @Test
  void undo_clearsEveryBoughtField_exactly() {
    ShoppingList parent = bulkParent();
    ShoppingListLine l = bulkLine(parent, "flour", "1.000", null);
    l.setFulfilmentStatus(LineFulfilmentStatus.BOUGHT);
    l.setBoughtQuantity(new BigDecimal("2.000"));
    l.setBoughtUnit("kg");
    l.setBoughtPricePence(250);
    l.setBoughtAt(NOW);
    l.setBoughtVia(BoughtVia.MANUAL);
    when(shoppingListDataGateway.findLineById(l.getId())).thenReturn(Optional.of(l));
    when(priceObservationWriter.write(any()))
        .thenReturn(GroceryTestData.priceObservation().id(UUID.randomUUID()).build());

    tier4Service().undoMarkBought(l.getId(), userId);

    // Every bought_* field is cleared (each setter call must survive).
    assertThat(l.getFulfilmentStatus()).isEqualTo(LineFulfilmentStatus.UNFILLED);
    assertThat(l.getBoughtQuantity()).isNull();
    assertThat(l.getBoughtUnit()).isNull();
    assertThat(l.getBoughtPricePence()).isNull();
    assertThat(l.getBoughtAt()).isNull();
    assertThat(l.getBoughtVia()).isNull();
  }

  @Test
  void bulk_inventoryItemPresent_stampedOntoEveryResult() {
    ShoppingList parent = bulkParent();
    ShoppingListLine a = bulkLine(parent, "flour", "1", 100);
    ShoppingListLine b = bulkLine(parent, "rice", "1", 100);
    List<ShoppingListLine> lines = List.of(a, b);
    when(shoppingListDataGateway.findLinesByIds(any())).thenReturn(new ArrayList<>(lines));
    when(priceObservationWriter.write(any()))
        .thenAnswer(inv -> GroceryTestData.priceObservation().id(UUID.randomUUID()).build());
    UUID inventoryId = UUID.randomUUID();
    var item =
        org.mockito.Mockito.mock(com.example.mealprep.provisions.api.dto.InventoryItemDto.class);
    when(item.id()).thenReturn(inventoryId);
    when(markBoughtInventoryBridge.applyBulk(any(), any(), any(), any()))
        .thenReturn(new GroceryImportResultDto(List.of(item), List.of(), List.of(), List.of()));

    List<MarkBoughtResultDto> results =
        tier4Service().bulkMarkBought(userId, bulkReq(parent, lines, 400));

    // The single inventory id (present) is stamped onto EVERY result (the L432-444 branch).
    assertThat(results).hasSize(2);
    assertThat(results).allMatch(r -> inventoryId.equals(r.inventoryItemId()));
  }

  @Test
  void markBought_noOverMark_noteIsNull() {
    ShoppingList parent = bulkParent();
    ShoppingListLine l = bulkLine(parent, "flour", "5.000", null); // requested 5
    when(shoppingListDataGateway.findLineById(l.getId())).thenReturn(Optional.of(l));
    when(markBoughtInventoryBridge.applySingle(any(), any(), any(), any()))
        .thenReturn(new GroceryImportResultDto(List.of(), List.of(), List.of(), List.of()));

    // bought == requested → not over-mark → overMarkNote returns null (not "").
    MarkBoughtRequest req =
        new MarkBoughtRequest(l.getId(), new BigDecimal("5.000"), "kg", null, null, null);
    MarkBoughtResultDto result = tier4Service().markBought(userId, req);

    assertThat(result.note()).isNull();
  }

  // ============================ getAggregate ============================

  @Test
  void getAggregate_blankKey_returnsEmpty_noGatewayCall() {
    PriceAggregateDto unused = agg("x", null, 1);
    assertThat(unused).isNotNull();
    Optional<PriceAggregateDto> result = tier4Service().getAggregate(userId, "   ", "tesco");

    assertThat(result).isEmpty();
    verifyNoInteractions(priceDataGateway, priceAggregator);
  }

  @Test
  void getAggregate_nullKey_returnsEmpty() {
    assertThat(tier4Service().getAggregate(userId, null, null)).isEmpty();
    verifyNoInteractions(priceDataGateway);
  }

  @Test
  void getAggregate_withStore_filtersRows_thenDelegatesToAggregator() {
    when(priceAggregator.windowStart(NOW)).thenReturn(WINDOW_START);
    List<PriceObservation> rows =
        new ArrayList<>(List.of(obs("flour", "tesco", 100), obs("flour", "asda", 200)));
    when(priceDataGateway.findRecentByKeyAcrossStores(userId, "flour", WINDOW_START))
        .thenReturn(rows);
    when(priceAggregator.aggregate(eq("flour"), eq("tesco"), any()))
        .thenReturn(Optional.of(agg("flour", "tesco", 100)));

    Optional<PriceAggregateDto> result = tier4Service().getAggregate(userId, "Flour", "tesco");

    assertThat(result).isPresent();
    assertThat(result.get().store()).isEqualTo("tesco");
    // Only the matching-store row is passed to the aggregator (store filter applied).
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PriceObservation>> passed = ArgumentCaptor.forClass(List.class);
    verify(priceAggregator).aggregate(eq("flour"), eq("tesco"), passed.capture());
    assertThat(passed.getValue()).hasSize(1);
    assertThat(passed.getValue().get(0).getStore()).isEqualTo("tesco");
  }

  @Test
  void getAggregate_nullStore_passesAllRowsUnfiltered() {
    when(priceAggregator.windowStart(NOW)).thenReturn(WINDOW_START);
    List<PriceObservation> rows =
        new ArrayList<>(List.of(obs("flour", "tesco", 100), obs("flour", "asda", 200)));
    when(priceDataGateway.findRecentByKeyAcrossStores(userId, "flour", WINDOW_START))
        .thenReturn(rows);
    when(priceAggregator.aggregate(eq("flour"), eq(null), any()))
        .thenReturn(Optional.of(agg("flour", null, 150)));

    tier4Service().getAggregate(userId, "flour", null);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PriceObservation>> passed = ArgumentCaptor.forClass(List.class);
    verify(priceAggregator).aggregate(eq("flour"), eq(null), passed.capture());
    assertThat(passed.getValue()).hasSize(2); // no store filter → both rows
  }

  // ============================ getAggregatesByKeys ============================

  @Test
  void getAggregatesByKeys_emptyAfterNormalisation_returnsEmpty_noQueries() {
    Map<String, PriceAggregateDto> out =
        tier4Service().getAggregatesByKeys(userId, List.of("  ", ""));

    assertThat(out).isEmpty();
    verifyNoInteractions(priceDataGateway, referencePriceSource);
  }

  @Test
  void getAggregatesByKeys_dedupesNormalisedKeys_bucketsObservations_andFallsBackPerKey() {
    when(priceAggregator.windowStart(NOW)).thenReturn(WINDOW_START);
    // "Flour" and "flour" normalise to the same key → one bucket; "rice" has no observations.
    List<PriceObservation> all = new ArrayList<>(List.of(obs("flour", "tesco", 100)));
    when(priceDataGateway.findRecentByKeys(eq(userId), any(), eq(WINDOW_START))).thenReturn(all);
    ReferencePrice riceRef =
        new ReferencePrice("rice", 80, "per_100g", new BigDecimal("0.2"), NOW, "ODbL");
    when(referencePriceSource.referencePrices(any())).thenReturn(Map.of("rice", riceRef));

    // flour: observation branch; rice: reference-fallback branch.
    when(priceAggregator.aggregate(eq("flour"), eq(null), any()))
        .thenReturn(Optional.of(agg("flour", null, 100)));
    when(priceAggregator.toAggregate(eq("rice"), eq(null), eq(riceRef), eq(NOW)))
        .thenReturn(Optional.of(agg("rice", null, 80)));

    Map<String, PriceAggregateDto> out =
        tier4Service().getAggregatesByKeys(userId, List.of("Flour", "flour", "rice"));

    assertThat(out).containsOnlyKeys("flour", "rice");
    assertThat(out.get("flour").pointEstimatePence()).isEqualTo(100);
    assertThat(out.get("rice").pointEstimatePence()).isEqualTo(80);
    // Exactly ONE observation query + ONE reference batch (the ≤5-SQL contract).
    verify(priceDataGateway, times(1)).findRecentByKeys(eq(userId), any(), eq(WINDOW_START));
    verify(referencePriceSource, times(1)).referencePrices(any());
    // The observation-bearing key uses aggregate(), NOT the reference fallback.
    verify(priceAggregator).aggregate(eq("flour"), eq(null), any());
    verify(priceAggregator, never()).toAggregate(eq("flour"), any(), any(), any());
  }

  @Test
  void getAggregatesByKeys_aggregatorEmpty_keyOmittedFromResult() {
    when(priceAggregator.windowStart(NOW)).thenReturn(WINDOW_START);
    when(priceDataGateway.findRecentByKeys(eq(userId), any(), eq(WINDOW_START)))
        .thenReturn(new ArrayList<>(List.of(obs("flour", "tesco", 100))));
    when(referencePriceSource.referencePrices(any())).thenReturn(Map.of());
    when(priceAggregator.aggregate(eq("flour"), eq(null), any())).thenReturn(Optional.empty());

    Map<String, PriceAggregateDto> out =
        tier4Service().getAggregatesByKeys(userId, List.of("flour"));

    assertThat(out).isEmpty(); // empty aggregate → key not put into the map
  }

  // ============================ getCrossStoreAggregatesByKey ============================

  @Test
  void getCrossStore_blankKey_returnsEmptyList_noQuery() {
    assertThat(tier4Service().getCrossStoreAggregatesByKey(userId, "  ")).isEmpty();
    verifyNoInteractions(priceDataGateway);
  }

  @Test
  void getCrossStore_noObservations_usesReferenceFallback() {
    when(priceAggregator.windowStart(NOW)).thenReturn(WINDOW_START);
    when(priceDataGateway.findRecentByKeyAcrossStores(userId, "flour", WINDOW_START))
        .thenReturn(new ArrayList<>());
    when(priceAggregator.referenceFallback("flour", null, NOW))
        .thenReturn(Optional.of(agg("flour", null, 90)));

    List<PriceAggregateDto> out = tier4Service().getCrossStoreAggregatesByKey(userId, "Flour");

    assertThat(out).hasSize(1);
    assertThat(out.get(0).pointEstimatePence()).isEqualTo(90);
    verify(priceAggregator).referenceFallback("flour", null, NOW);
  }

  @Test
  void getCrossStore_noObservations_unmappedKey_returnsEmptyList() {
    when(priceAggregator.windowStart(NOW)).thenReturn(WINDOW_START);
    when(priceDataGateway.findRecentByKeyAcrossStores(userId, "novel", WINDOW_START))
        .thenReturn(new ArrayList<>());
    when(priceAggregator.referenceFallback("novel", null, NOW)).thenReturn(Optional.empty());

    assertThat(tier4Service().getCrossStoreAggregatesByKey(userId, "novel")).isEmpty();
  }

  @Test
  void getCrossStore_bucketsByStore_oneAggregatePerStore_insertionOrdered() {
    when(priceAggregator.windowStart(NOW)).thenReturn(WINDOW_START);
    List<PriceObservation> rows =
        new ArrayList<>(
            List.of(
                obs("flour", "tesco", 100), obs("flour", "asda", 200), obs("flour", "tesco", 110)));
    when(priceDataGateway.findRecentByKeyAcrossStores(userId, "flour", WINDOW_START))
        .thenReturn(rows);
    when(priceAggregator.aggregate(eq("flour"), eq("tesco"), any()))
        .thenReturn(Optional.of(agg("flour", "tesco", 105)));
    when(priceAggregator.aggregate(eq("flour"), eq("asda"), any()))
        .thenReturn(Optional.of(agg("flour", "asda", 200)));

    List<PriceAggregateDto> out = tier4Service().getCrossStoreAggregatesByKey(userId, "flour");

    // Two distinct stores → two aggregates, in first-seen order (tesco then asda).
    assertThat(out).hasSize(2);
    assertThat(out.get(0).store()).isEqualTo("tesco");
    assertThat(out.get(1).store()).isEqualTo("asda");
    // The tesco bucket carried BOTH tesco rows.
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PriceObservation>> tescoRows = ArgumentCaptor.forClass(List.class);
    verify(priceAggregator).aggregate(eq("flour"), eq("tesco"), tescoRows.capture());
    assertThat(tescoRows.getValue()).hasSize(2);
  }

  @Test
  void getCrossStore_aggregatorEmptyForOneStore_thatStoreOmitted() {
    when(priceAggregator.windowStart(NOW)).thenReturn(WINDOW_START);
    when(priceDataGateway.findRecentByKeyAcrossStores(userId, "flour", WINDOW_START))
        .thenReturn(
            new ArrayList<>(List.of(obs("flour", "tesco", 100), obs("flour", "asda", 200))));
    when(priceAggregator.aggregate(eq("flour"), eq("tesco"), any()))
        .thenReturn(Optional.of(agg("flour", "tesco", 100)));
    when(priceAggregator.aggregate(eq("flour"), eq("asda"), any())).thenReturn(Optional.empty());

    List<PriceAggregateDto> out = tier4Service().getCrossStoreAggregatesByKey(userId, "flour");

    assertThat(out).hasSize(1);
    assertThat(out.get(0).store()).isEqualTo("tesco");
  }

  // ============================ getObservations / getObservationsByMappingKey
  // ============================

  @Test
  void getObservations_mapsEachRowThroughMapper() {
    Pageable pageable = PageRequest.of(0, 10);
    PriceObservation row = obs("flour", "tesco", 100);
    Page<PriceObservation> page = new PageImpl<>(List.of(row), pageable, 1);
    when(priceDataGateway.findObservationsByUser(userId, pageable)).thenReturn(page);
    PriceObservationDto dto = dto(row.getId());
    when(priceObservationMapper.toDto(row)).thenReturn(dto);

    Page<PriceObservationDto> out = tier4Service().getObservations(userId, pageable);

    assertThat(out.getContent()).containsExactly(dto);
    verify(priceObservationMapper).toDto(row);
  }

  @Test
  void getObservationsByMappingKey_blankKey_returnsEmptyPage_noQuery() {
    Pageable pageable = PageRequest.of(0, 10);
    Page<PriceObservationDto> out =
        tier4Service().getObservationsByMappingKey(userId, "  ", pageable);

    assertThat(out.getContent()).isEmpty();
    verify(priceDataGateway, never()).findObservationsByHouseholdKey(any(), any(), any());
  }

  @Test
  void getObservationsByMappingKey_validKey_normalises_thenQueries_andMaps() {
    Pageable pageable = PageRequest.of(0, 10);
    PriceObservation row = obs("chicken breast", "tesco", 110);
    when(priceDataGateway.findObservationsByHouseholdKey(userId, "chicken breast", pageable))
        .thenReturn(new PageImpl<>(List.of(row), pageable, 1));
    PriceObservationDto dto = dto(row.getId());
    when(priceObservationMapper.toDto(row)).thenReturn(dto);

    Page<PriceObservationDto> out =
        tier4Service().getObservationsByMappingKey(userId, "  Chicken   Breast ", pageable);

    assertThat(out.getContent()).containsExactly(dto);
    // The normalised key is what hits the gateway.
    verify(priceDataGateway).findObservationsByHouseholdKey(userId, "chicken breast", pageable);
  }

  // ============================ recordManualPrice ============================

  @Test
  void recordManualPrice_assemblesManualWriteCommand_withExactFields() {
    RecordManualPriceRequest req =
        new RecordManualPriceRequest("flour", "tesco", 250, new BigDecimal("2.000"), "kg", NOW);
    PriceObservation saved = obs("flour", "tesco", 125);
    when(priceObservationWriter.write(any())).thenReturn(saved);
    PriceObservationDto dto = dto(saved.getId());
    when(priceObservationMapper.toDto(saved)).thenReturn(dto);

    PriceObservationDto out = tier4Service().recordManualPrice(userId, req);

    assertThat(out).isEqualTo(dto);
    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter).write(cmd.capture());
    PriceObservationWriter.WriteCommand w = cmd.getValue();
    assertThat(w.userId()).isEqualTo(userId);
    assertThat(w.householdId()).isEqualTo(userId); // single-user mode: userId doubles as household
    assertThat(w.ingredientMappingKey()).isEqualTo("flour");
    assertThat(w.store()).isEqualTo("tesco");
    assertThat(w.quantity()).isEqualByComparingTo(new BigDecimal("2.000"));
    assertThat(w.quantityUnit()).isEqualTo("kg");
    assertThat(w.paidTotalPence()).isEqualTo(250);
    assertThat(w.currency()).isEqualTo("GBP");
    assertThat(w.source()).isEqualTo(PriceSource.MANUAL);
    assertThat(w.observedAt()).isEqualTo(NOW);
  }

  // ============================ refreshOnDemand ============================

  @Test
  void refreshOnDemand_countsNormalisedNonBlankKeys_writesNoObservations() {
    // "Flour" + "flour" both normalise to "flour" but are counted as two distinct entries (the
    // method counts per supplied element, not per distinct key); the blank entry is dropped.
    RefreshPricesRequest req =
        new RefreshPricesRequest(userId, List.of("Flour", "flour", "   ", "rice"), false);

    RefreshPricesResultDto out = tier4Service().refreshOnDemand(userId, req);

    assertThat(out.observationsWritten()).isEqualTo(0);
    assertThat(out.ingredientsRefreshed()).isEqualTo(3); // flour, flour, rice — blank dropped
    assertThat(out.aiUnavailableFallbackUsed()).isFalse();
    assertThat(out.fallbackMessage()).isNull();
    verifyNoInteractions(priceObservationWriter);
  }

  @Test
  void refreshOnDemand_nullKeys_refreshesZero() {
    RefreshPricesRequest req = new RefreshPricesRequest(userId, null, true);

    RefreshPricesResultDto out = tier4Service().refreshOnDemand(userId, req);

    assertThat(out.ingredientsRefreshed()).isEqualTo(0);
    assertThat(out.observationsWritten()).isEqualTo(0);
  }

  // ============================ runScheduledBackgroundRefresh (01g) ============================

  @Test
  void scheduledRefresh_nullUser_returnsImmediately_noCollaborators() {
    service(
            providersOf(provider),
            providerOf(recipeQueryService),
            providerOf(guardrails),
            providerOf(orderGateway))
        .runScheduledBackgroundRefresh(null);

    verifyNoInteractions(guardrails, orderGateway, recipeQueryService, priceObservationWriter);
  }

  @Test
  void scheduledRefresh_guardrailSkip_stopsBeforeOrderGateway() {
    when(guardrails.preflight(userId, PriceFreshnessGuardrails.RefreshKind.SCHEDULED))
        .thenReturn(PriceFreshnessGuardrails.Decision.SKIP);

    service(
            providersOf(provider),
            providerOf(recipeQueryService),
            providerOf(guardrails),
            providerOf(orderGateway))
        .runScheduledBackgroundRefresh(userId);

    verify(guardrails).preflight(userId, PriceFreshnessGuardrails.RefreshKind.SCHEDULED);
    verifyNoInteractions(orderGateway, recipeQueryService, priceObservationWriter);
  }

  @Test
  void scheduledRefresh_guardrailBlock_stopsBeforeOrderGateway() {
    when(guardrails.preflight(userId, PriceFreshnessGuardrails.RefreshKind.SCHEDULED))
        .thenReturn(PriceFreshnessGuardrails.Decision.BLOCK);

    service(
            providersOf(provider),
            providerOf(recipeQueryService),
            providerOf(guardrails),
            providerOf(orderGateway))
        .runScheduledBackgroundRefresh(userId);

    verifyNoInteractions(orderGateway, recipeQueryService, priceObservationWriter);
  }

  @Test
  void scheduledRefresh_noGuardrailBean_proceeds_butNoOrderGateway_returns() {
    // guardrailsProvider empty (getIfAvailable null) → skip the guardrail branch entirely;
    // orderGateway also absent → return before resolving keys.
    service(
            providersOf(provider),
            providerOf(recipeQueryService),
            new EmptyObjectProvider<>(),
            new EmptyObjectProvider<>())
        .runScheduledBackgroundRefresh(userId);

    verifyNoInteractions(orderGateway, recipeQueryService, priceObservationWriter);
  }

  @Test
  void scheduledRefresh_noEnabledProviderState_skips() {
    when(guardrails.preflight(userId, PriceFreshnessGuardrails.RefreshKind.SCHEDULED))
        .thenReturn(PriceFreshnessGuardrails.Decision.ALLOW);
    // A disabled state must be filtered out → empty active state → skip.
    GroceryProviderState disabled =
        GroceryTestData.providerState().userId(userId).enabled(false).build();
    when(orderGateway.findProviderStatesByUserId(userId)).thenReturn(List.of(disabled));

    service(
            providersOf(provider),
            providerOf(recipeQueryService),
            providerOf(guardrails),
            providerOf(orderGateway))
        .runScheduledBackgroundRefresh(userId);

    verifyNoInteractions(recipeQueryService, priceObservationWriter);
  }

  @Test
  void scheduledRefresh_scheduledRefreshDisabledState_skips() {
    when(guardrails.preflight(userId, PriceFreshnessGuardrails.RefreshKind.SCHEDULED))
        .thenReturn(PriceFreshnessGuardrails.Decision.ALLOW);
    GroceryProviderState notScheduled =
        GroceryTestData.providerState()
            .userId(userId)
            .enabled(true)
            .scheduledRefreshEnabled(false)
            .build();
    when(orderGateway.findProviderStatesByUserId(userId)).thenReturn(List.of(notScheduled));

    service(
            providersOf(provider),
            providerOf(recipeQueryService),
            providerOf(guardrails),
            providerOf(orderGateway))
        .runScheduledBackgroundRefresh(userId);

    verifyNoInteractions(recipeQueryService, priceObservationWriter);
  }

  @Test
  void scheduledRefresh_noProviderBeanForKey_skips() {
    when(guardrails.preflight(userId, PriceFreshnessGuardrails.RefreshKind.SCHEDULED))
        .thenReturn(PriceFreshnessGuardrails.Decision.ALLOW);
    GroceryProviderState state =
        GroceryTestData.providerState().userId(userId).providerKey("tesco").build();
    when(orderGateway.findProviderStatesByUserId(userId)).thenReturn(List.of(state));
    // The only provider bean answers "fake", not "tesco" → no match → skip.
    when(provider.providerKey()).thenReturn("fake");

    service(
            providersOf(provider),
            providerOf(recipeQueryService),
            providerOf(guardrails),
            providerOf(orderGateway))
        .runScheduledBackgroundRefresh(userId);

    verifyNoInteractions(recipeQueryService, priceObservationWriter);
  }

  @Test
  void scheduledRefresh_noKeys_skipsQuote() {
    when(guardrails.preflight(userId, PriceFreshnessGuardrails.RefreshKind.SCHEDULED))
        .thenReturn(PriceFreshnessGuardrails.Decision.ALLOW);
    GroceryProviderState state =
        GroceryTestData.providerState().userId(userId).providerKey("fake").build();
    when(orderGateway.findProviderStatesByUserId(userId)).thenReturn(List.of(state));
    when(provider.providerKey()).thenReturn("fake");
    // recipe service returns no keys → resolveTopUsedKeys empty → no quote.
    when(recipeQueryService.findUserRecipeIngredientKeys(userId)).thenReturn(Map.of());

    service(
            providersOf(provider),
            providerOf(recipeQueryService),
            providerOf(guardrails),
            providerOf(orderGateway))
        .runScheduledBackgroundRefresh(userId);

    verifyNoInteractions(priceObservationWriter);
  }

  @Test
  void scheduledRefresh_happyPath_quotesAndWritesObservations() throws Exception {
    when(guardrails.preflight(userId, PriceFreshnessGuardrails.RefreshKind.SCHEDULED))
        .thenReturn(PriceFreshnessGuardrails.Decision.ALLOW);
    GroceryProviderState state =
        GroceryTestData.providerState()
            .userId(userId)
            .providerKey("fake")
            .refreshTopNIngredients(2)
            .build();
    when(orderGateway.findProviderStatesByUserId(userId)).thenReturn(List.of(state));
    when(provider.providerKey()).thenReturn("fake");
    when(recipeQueryService.findUserRecipeIngredientKeys(userId))
        .thenReturn(Map.of(UUID.randomUUID(), List.of("flour", "rice")));

    // The provider quotes both lines; map keyed by the synthetic line ids in the draft.
    when(provider.quote(any()))
        .thenAnswer(
            inv -> {
              BasketDraft draft = inv.getArgument(0);
              Map<UUID, QuoteLineResult> lineResults = new LinkedHashMap<>();
              for (var line : draft.lines()) {
                lineResults.put(
                    line.groceryOrderLineId(),
                    new QuoteLineResult(
                        OrderLineStatus.ADDED, "sku-" + line.ingredientMappingKey(), 200, 1, null));
              }
              return new QuoteResult("prov-order-1", lineResults, 400, "GBP", NOW);
            });

    service(
            providersOf(provider),
            providerOf(recipeQueryService),
            providerOf(guardrails),
            providerOf(orderGateway))
        .runScheduledBackgroundRefresh(userId);

    // One QUOTE observation per quoted line (2 keys → 2 writes).
    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter, times(2)).write(cmd.capture());
    assertThat(cmd.getAllValues()).allMatch(w -> w.source() == PriceSource.QUOTE);
    assertThat(cmd.getAllValues()).allMatch(w -> "fake".equals(w.store()));
    assertThat(cmd.getAllValues()).allMatch(w -> w.userId().equals(userId));
    assertThat(cmd.getAllValues()).allMatch(w -> w.householdId().equals(userId));
    // total pence = quotedUnitPence(200) * packCount(1).
    assertThat(cmd.getAllValues()).allMatch(w -> w.paidTotalPence() == 200);
  }

  @Test
  void scheduledRefresh_topNFromConfigDefault_whenStateTopNNotPositive() throws Exception {
    when(guardrails.preflight(userId, PriceFreshnessGuardrails.RefreshKind.SCHEDULED))
        .thenReturn(PriceFreshnessGuardrails.Decision.ALLOW);
    // refreshTopNIngredients = 0 → falls back to config default (50). With 3 keys all survive.
    GroceryProviderState state =
        GroceryTestData.providerState()
            .userId(userId)
            .providerKey("fake")
            .refreshTopNIngredients(0)
            .build();
    when(orderGateway.findProviderStatesByUserId(userId)).thenReturn(List.of(state));
    when(provider.providerKey()).thenReturn("fake");
    when(recipeQueryService.findUserRecipeIngredientKeys(userId))
        .thenReturn(Map.of(UUID.randomUUID(), List.of("flour", "rice", "sugar")));
    when(provider.quote(any()))
        .thenAnswer(
            inv -> {
              BasketDraft draft = inv.getArgument(0);
              assertThat(draft.lines()).hasSize(3); // top-N default kept all 3
              Map<UUID, QuoteLineResult> lineResults = new LinkedHashMap<>();
              for (var line : draft.lines()) {
                lineResults.put(
                    line.groceryOrderLineId(),
                    new QuoteLineResult(OrderLineStatus.ADDED, "sku", 100, null, null));
              }
              return new QuoteResult("o", lineResults, 300, null, NOW);
            });

    service(
            providersOf(provider),
            providerOf(recipeQueryService),
            providerOf(guardrails),
            providerOf(orderGateway))
        .runScheduledBackgroundRefresh(userId);

    // packCountResolved null → defaults to 1; currency null → "GBP".
    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter, times(3)).write(cmd.capture());
    assertThat(cmd.getAllValues()).allMatch(w -> w.paidTotalPence() == 100);
    assertThat(cmd.getAllValues()).allMatch(w -> "GBP".equals(w.currency()));
    assertThat(cmd.getAllValues()).allMatch(w -> w.packCount() == 1);
  }

  // ============================ resolveTopUsedKeys (01g) ============================

  @Test
  void resolveTopUsedKeys_noRecipeBean_returnsEmpty() {
    GroceryServiceImpl svc =
        service(
            providersOf(provider),
            new EmptyObjectProvider<>(),
            providerOf(guardrails),
            providerOf(orderGateway));

    assertThat(svc.resolveTopUsedKeys(userId, 5)).isEmpty();
  }

  @Test
  void resolveTopUsedKeys_nullOrEmptyMap_returnsEmpty() {
    GroceryServiceImpl svc =
        service(
            providersOf(provider),
            providerOf(recipeQueryService),
            providerOf(guardrails),
            providerOf(orderGateway));
    when(recipeQueryService.findUserRecipeIngredientKeys(userId)).thenReturn(Map.of());

    assertThat(svc.resolveTopUsedKeys(userId, 5)).isEmpty();
  }

  @Test
  void resolveTopUsedKeys_countsOccurrences_sortsByFrequencyDesc_limitsToTopN() {
    GroceryServiceImpl svc =
        service(
            providersOf(provider),
            providerOf(recipeQueryService),
            providerOf(guardrails),
            providerOf(orderGateway));
    // flour appears 3x, rice 2x, sugar 1x. blank/null entries are skipped.
    Map<UUID, List<String>> byRecipe = new LinkedHashMap<>();
    byRecipe.put(UUID.randomUUID(), List.of("Flour", "rice", "  "));
    byRecipe.put(UUID.randomUUID(), List.of("flour", "Rice", "sugar"));
    byRecipe.put(UUID.randomUUID(), java.util.Arrays.asList("flour", null));
    when(recipeQueryService.findUserRecipeIngredientKeys(userId)).thenReturn(byRecipe);

    List<String> top2 = svc.resolveTopUsedKeys(userId, 2);

    // top-N=2 → most frequent two, in count-desc order.
    assertThat(top2).containsExactly("flour", "rice");
  }

  @Test
  void resolveTopUsedKeys_allKeysBlank_returnsEmpty() {
    GroceryServiceImpl svc =
        service(
            providersOf(provider),
            providerOf(recipeQueryService),
            providerOf(guardrails),
            providerOf(orderGateway));
    when(recipeQueryService.findUserRecipeIngredientKeys(userId))
        .thenReturn(Map.of(UUID.randomUUID(), List.of("  ", "")));

    assertThat(svc.resolveTopUsedKeys(userId, 5)).isEmpty();
  }

  // ============================ quoteAndWriteObservations (01g) ============================

  @Test
  void quoteAndWrite_providerUnavailable_writesNothing() throws Exception {
    GroceryProviderState state =
        GroceryTestData.providerState().userId(userId).providerKey("fake").build();
    when(provider.quote(any()))
        .thenThrow(new ProviderUnavailableException("fake", "login_required", "down"));

    tier4Service().quoteAndWriteObservations(userId, state, provider, List.of("flour"));

    verifyNoInteractions(priceObservationWriter);
  }

  @Test
  void quoteAndWrite_aiUnavailable_writesNothing() throws Exception {
    GroceryProviderState state =
        GroceryTestData.providerState().userId(userId).providerKey("fake").build();
    when(provider.quote(any())).thenThrow(new AiUnavailableException("cap"));

    tier4Service().quoteAndWriteObservations(userId, state, provider, List.of("flour"));

    verifyNoInteractions(priceObservationWriter);
  }

  @Test
  void quoteAndWrite_nullResult_writesNothing() throws Exception {
    GroceryProviderState state =
        GroceryTestData.providerState().userId(userId).providerKey("fake").build();
    when(provider.quote(any())).thenReturn(null);

    tier4Service().quoteAndWriteObservations(userId, state, provider, List.of("flour"));

    verifyNoInteractions(priceObservationWriter);
  }

  @Test
  void quoteAndWrite_nullLineResults_writesNothing() throws Exception {
    GroceryProviderState state =
        GroceryTestData.providerState().userId(userId).providerKey("fake").build();
    when(provider.quote(any())).thenReturn(new QuoteResult("o", null, 0, "GBP", NOW));

    tier4Service().quoteAndWriteObservations(userId, state, provider, List.of("flour"));

    verifyNoInteractions(priceObservationWriter);
  }

  @Test
  void quoteAndWrite_skipsLinesWithNullPrice_andUnknownLineIds() throws Exception {
    GroceryProviderState state =
        GroceryTestData.providerState().userId(userId).providerKey("fake").build();
    when(provider.quote(any()))
        .thenAnswer(
            inv -> {
              BasketDraft draft = inv.getArgument(0);
              Map<UUID, QuoteLineResult> lineResults = new LinkedHashMap<>();
              // First real line → null quotedUnitPence → skipped.
              lineResults.put(
                  draft.lines().get(0).groceryOrderLineId(),
                  new QuoteLineResult(OrderLineStatus.UNAVAILABLE, null, null, 1, "oos"));
              // Second real line → priced → written.
              lineResults.put(
                  draft.lines().get(1).groceryOrderLineId(),
                  new QuoteLineResult(OrderLineStatus.ADDED, "sku", 150, 2, null));
              // An unknown line id not in the draft → no key → skipped.
              lineResults.put(
                  UUID.randomUUID(),
                  new QuoteLineResult(OrderLineStatus.ADDED, "sku", 999, 1, null));
              return new QuoteResult("o", lineResults, 300, "GBP", NOW);
            });

    tier4Service().quoteAndWriteObservations(userId, state, provider, List.of("flour", "rice"));

    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter, times(1)).write(cmd.capture());
    // The written line is the priced one: total = quotedUnitPence(150) * packCount(2) = 300.
    assertThat(cmd.getValue().paidTotalPence()).isEqualTo(300);
    assertThat(cmd.getValue().packCount()).isEqualTo(2);
    assertThat(cmd.getValue().source()).isEqualTo(PriceSource.QUOTE);
    assertThat(cmd.getValue().ingredientMappingKey()).isEqualTo("rice");
    assertThat(cmd.getValue().providerProductId()).isEqualTo("sku");
  }

  // ---- fixtures ----

  private PriceObservationDto dto(UUID id) {
    return new PriceObservationDto(
        id,
        userId,
        userId,
        "flour",
        "tesco",
        null,
        null,
        null,
        new BigDecimal("1.000"),
        "kg",
        100,
        100,
        "GBP",
        PriceSource.MANUAL,
        new BigDecimal("0.700"),
        null,
        null,
        NOW,
        null);
  }
}
