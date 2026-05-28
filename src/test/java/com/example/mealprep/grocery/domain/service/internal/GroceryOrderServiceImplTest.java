package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.core.lock.LockKey;
import com.example.mealprep.core.lock.LockService;
import com.example.mealprep.grocery.api.dto.CancelOrderRequest;
import com.example.mealprep.grocery.api.dto.CreateOrderRequest;
import com.example.mealprep.grocery.api.dto.GroceryOrderDto;
import com.example.mealprep.grocery.api.dto.PlaceOrderRequest;
import com.example.mealprep.grocery.api.dto.ProviderConnectionRequest;
import com.example.mealprep.grocery.api.dto.QuoteRequest;
import com.example.mealprep.grocery.api.dto.ResolveSubstitutionRequest;
import com.example.mealprep.grocery.api.mapper.GroceryOrderMapper;
import com.example.mealprep.grocery.api.mapper.GroceryProviderStateMapper;
import com.example.mealprep.grocery.api.mapper.GrocerySubstitutionProposalMapper;
import com.example.mealprep.grocery.config.GroceryConfig;
import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderLine;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.domain.entity.GroceryProviderState;
import com.example.mealprep.grocery.domain.entity.GrocerySubstitutionProposal;
import com.example.mealprep.grocery.domain.entity.OrderLineStatus;
import com.example.mealprep.grocery.domain.entity.PriceSource;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.domain.entity.ShoppingListLineType;
import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import com.example.mealprep.grocery.domain.service.internal.providers.BasketDraft;
import com.example.mealprep.grocery.domain.service.internal.providers.GroceryProvider;
import com.example.mealprep.grocery.domain.service.internal.providers.OrderStatus;
import com.example.mealprep.grocery.domain.service.internal.providers.PlaceOrderResult;
import com.example.mealprep.grocery.domain.service.internal.providers.ProviderPartialFailureException;
import com.example.mealprep.grocery.domain.service.internal.providers.ProviderUnavailableException;
import com.example.mealprep.grocery.domain.service.internal.providers.QuoteLineResult;
import com.example.mealprep.grocery.domain.service.internal.providers.QuoteResult;
import com.example.mealprep.grocery.event.GroceryOrderCancelledEvent;
import com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent;
import com.example.mealprep.grocery.event.GroceryOrderDeliveredEvent;
import com.example.mealprep.grocery.event.GroceryOrderPlacedEvent;
import com.example.mealprep.grocery.event.GroceryOrderQuotedEvent;
import com.example.mealprep.grocery.exception.GroceryOrderNotFoundException;
import com.example.mealprep.grocery.exception.GrocerySubstitutionProposalNotFoundException;
import com.example.mealprep.grocery.exception.IllegalOrderTransitionException;
import com.example.mealprep.grocery.exception.IllegalSubstitutionStateException;
import com.example.mealprep.grocery.exception.OrderConcurrencyConflictException;
import com.example.mealprep.grocery.exception.ProviderNotConfiguredException;
import com.example.mealprep.grocery.testdata.GroceryTestData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit test for {@link GroceryOrderServiceImpl} (grocery-01e). Covers: createDraft (DRAFT line
 * snapshot), quote (success + ProviderUnavailable/AiUnavailable failure-forward), placeOrder
 * (success + PLACED_PARTIAL fail-forward + auto-advance to AWAITING_USER_CONFIRMATION), cancel,
 * markUserConfirmed, refreshStatus, markDelivered, resolveSubstitution (ACCEPTED/REJECTED + illegal
 * decisions/states), single-flight 409, ownership 404, provider-not-configured 422, helpers
 * (applyStatusTotals null gates, clearFailure). Pure unit test — collaborators mocked.
 */
@ExtendWith(MockitoExtension.class)
class GroceryOrderServiceImplTest {

  private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private GroceryOrderDataGateway dataGateway;
  @Mock private BasketDraftAssembler basketDraftAssembler;
  @Mock private PriceObservationWriter priceObservationWriter;
  @Mock private OrderFailureRecorder failureRecorder;
  @Mock private SubstitutionPersister substitutionPersister;
  @Mock private OrderReconciler orderReconciler;
  @Mock private GroceryOrderMapper orderMapper;
  @Mock private GrocerySubstitutionProposalMapper proposalMapper;
  @Mock private GroceryProviderStateMapper providerStateMapper;
  @Mock private LockService lockService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private GroceryProvider provider;

  // Real instance, not mock — its tested behaviour is the legal-edges table.
  private final OrderStateMachine stateMachine = new OrderStateMachine();

  private final GroceryConfig config =
      new GroceryConfig(
          new GroceryConfig.AggregatorConfig(90, 2.0, 90),
          new GroceryConfig.ConfidenceWeightsConfig(1.0, 0.85, 0.7, 0.4, 0.15),
          new GroceryConfig.InflationConfig(0.005),
          new GroceryConfig.FreshnessConfig(8, 50),
          new GroceryConfig.SchedulerConfig("0 0 4 * * SUN", "0 0 * * * *", "0 0 5 * * *"),
          new GroceryConfig.OrderConfig(300, 24));

  private ObjectProvider<GroceryProvider> providers;
  private GroceryOrderServiceImpl service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    providers = org.mockito.Mockito.mock(ObjectProvider.class);
    lenient().when(provider.providerKey()).thenReturn("fake");
    lenient().when(providers.stream()).thenAnswer(inv -> Stream.of(provider));
    service =
        new GroceryOrderServiceImpl(
            dataGateway,
            basketDraftAssembler,
            stateMachine,
            priceObservationWriter,
            failureRecorder,
            substitutionPersister,
            orderReconciler,
            orderMapper,
            proposalMapper,
            providerStateMapper,
            lockService,
            config,
            eventPublisher,
            providers,
            clock);
    // Default lock always succeeds.
    lenient().when(lockService.tryAcquire(any(LockKey.class))).thenReturn(true);
    // Default reload returns a DTO with sensible defaults so each lifecycle method has something
    // to return after its happy-path persistence.
    lenient().when(dataGateway.findOrderWithLinesById(any())).thenReturn(Optional.empty());
    lenient()
        .when(orderMapper.toDto(any()))
        .thenAnswer(inv -> dto((GroceryOrder) inv.getArgument(0)));
  }

  // ============================== fixtures ==============================

  private static final UUID USER_ID = UUID.randomUUID();

  private GroceryOrder order(GroceryOrderStatus status, GroceryOrderLine... lines) {
    UUID orderId = UUID.randomUUID();
    GroceryOrder order =
        GroceryOrder.builder()
            .id(orderId)
            .userId(USER_ID)
            .householdId(UUID.randomUUID())
            .shoppingListId(UUID.randomUUID())
            .providerKey("fake")
            .status(status)
            .currency("GBP")
            .traceId(orderId)
            .automationFailureLog(new ArrayList<>())
            .lines(new ArrayList<>(List.of(lines)))
            .build();
    for (GroceryOrderLine l : lines) {
      l.setGroceryOrder(order);
    }
    return order;
  }

  private GroceryOrderLine line(String key, OrderLineStatus status) {
    return GroceryOrderLine.builder()
        .id(UUID.randomUUID())
        .ingredientMappingKey(key)
        .displayName(capitalise(key))
        .providerProductId("sku-" + key)
        .quantityRequested(new BigDecimal("1.000"))
        .quantityUnit("kg")
        .packSizeG(500)
        .packCountRequested(2)
        .lineStatus(status)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build();
  }

  private GroceryProviderState providerState(boolean enabled) {
    return GroceryProviderState.builder()
        .id(UUID.randomUUID())
        .userId(USER_ID)
        .providerKey("fake")
        .enabled(enabled)
        .consecutiveFailures(2)
        .lastFailureReason("prior failure")
        .refreshTopNIngredients(8)
        .build();
  }

  private GroceryOrderDto dto(GroceryOrder o) {
    return new GroceryOrderDto(
        o.getId(),
        o.getUserId(),
        o.getHouseholdId(),
        o.getShoppingListId(),
        o.getProviderKey(),
        o.getProviderOrderId(),
        o.getStatus(),
        o.getStatusReason(),
        o.getQuotedTotalPence(),
        o.getConfirmedTotalPence(),
        o.getPaidTotalPence(),
        o.getCurrency(),
        o.getDeliverySlotStart(),
        o.getDeliverySlotEnd(),
        o.getConfirmLink(),
        o.getPlacedAt(),
        o.getConfirmedAt(),
        o.getDeliveredAt(),
        o.getReconciledAt(),
        o.getCancelledAt(),
        o.getCancelReason(),
        o.getLastStatusCheckAt(),
        List.of(),
        null,
        0L);
  }

  private void stubReloadReturns(GroceryOrder order) {
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
  }

  private static String capitalise(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  // ============================== createDraft ==============================

  @Test
  void createDraft_missingShoppingList_throws404() {
    UUID listId = UUID.randomUUID();
    when(dataGateway.findShoppingListWithLinesById(listId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.createDraft(USER_ID, new CreateOrderRequest(listId, "fake")))
        .isInstanceOf(GroceryOrderNotFoundException.class);
    verify(dataGateway, never()).saveAndFlushOrder(any());
  }

  @Test
  void createDraft_providerDisabled_throws422() {
    ShoppingList list = shoppingList();
    when(dataGateway.findShoppingListWithLinesById(list.getId())).thenReturn(Optional.of(list));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(false)));

    assertThatThrownBy(
            () -> service.createDraft(USER_ID, new CreateOrderRequest(list.getId(), "fake")))
        .isInstanceOf(ProviderNotConfiguredException.class);
    verify(dataGateway, never()).saveAndFlushOrder(any());
  }

  @Test
  void createDraft_noProviderState_throws422() {
    ShoppingList list = shoppingList();
    when(dataGateway.findShoppingListWithLinesById(list.getId())).thenReturn(Optional.of(list));
    when(dataGateway.findProviderState(USER_ID, "fake")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.createDraft(USER_ID, new CreateOrderRequest(list.getId(), "fake")))
        .isInstanceOf(ProviderNotConfiguredException.class);
  }

  @Test
  void createDraft_snapshotsLines_setsStatusDraft_setsCurrencyGBP() {
    ShoppingList list = shoppingList();
    when(dataGateway.findShoppingListWithLinesById(list.getId())).thenReturn(Optional.of(list));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(dataGateway.saveAndFlushOrder(any()))
        .thenAnswer(
            inv -> {
              GroceryOrder saved = inv.getArgument(0, GroceryOrder.class);
              when(dataGateway.findOrderWithLinesById(saved.getId()))
                  .thenReturn(Optional.of(saved));
              return saved;
            });

    service.createDraft(USER_ID, new CreateOrderRequest(list.getId(), "fake"));

    ArgumentCaptor<GroceryOrder> captor = ArgumentCaptor.forClass(GroceryOrder.class);
    verify(dataGateway).saveAndFlushOrder(captor.capture());
    GroceryOrder created = captor.getValue();
    assertThat(created.getStatus()).isEqualTo(GroceryOrderStatus.DRAFT);
    assertThat(created.getCurrency()).isEqualTo("GBP");
    assertThat(created.getProviderKey()).isEqualTo("fake");
    assertThat(created.getShoppingListId()).isEqualTo(list.getId());
    assertThat(created.getHouseholdId()).isEqualTo(list.getHouseholdId());
    assertThat(created.getUserId()).isEqualTo(USER_ID);
    // Each shopping-list line was deep-copied into an order line with status QUEUED.
    assertThat(created.getLines()).hasSize(list.getLines().size());
    assertThat(created.getLines()).allMatch(l -> l.getLineStatus() == OrderLineStatus.QUEUED);
    assertThat(created.getLines().get(0).getShoppingListLineId())
        .isEqualTo(list.getLines().get(0).getId());
  }

  // ============================== quote ==============================

  @Test
  void quote_singleFlightFailed_throws409() {
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, line("flour", OrderLineStatus.QUEUED));
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(lockService.tryAcquire(any(LockKey.class))).thenReturn(false);

    assertThatThrownBy(() -> service.quote(USER_ID, new QuoteRequest(order.getId())))
        .isInstanceOf(OrderConcurrencyConflictException.class);
    verifyNoInteractions(provider, basketDraftAssembler);
  }

  @Test
  void quote_orderNotOwned_throws404() {
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, line("flour", OrderLineStatus.QUEUED));
    order.setUserId(UUID.randomUUID()); // different owner
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> service.quote(USER_ID, new QuoteRequest(order.getId())))
        .isInstanceOf(GroceryOrderNotFoundException.class);
  }

  @Test
  void quote_orderMissing_throws404() {
    UUID id = UUID.randomUUID();
    when(dataGateway.findOrderWithLinesById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.quote(USER_ID, new QuoteRequest(id)))
        .isInstanceOf(GroceryOrderNotFoundException.class);
  }

  @Test
  void quote_illegalTransition_throws409_fromPlacedOrder() {
    // PLACED → QUOTED is not a legal edge.
    GroceryOrder order = order(GroceryOrderStatus.PLACED, line("flour", OrderLineStatus.ADDED));
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> service.quote(USER_ID, new QuoteRequest(order.getId())))
        .isInstanceOf(IllegalOrderTransitionException.class);
  }

  @Test
  void quote_providerUnavailable_recordsFailure_throwsHttpMapped503() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    BasketDraft draft = new BasketDraft(order.getId(), USER_ID, List.of(), null);
    when(basketDraftAssembler.assemble(order)).thenReturn(draft);
    when(provider.quote(draft))
        .thenThrow(new ProviderUnavailableException("fake", "login_required", "session expired"));

    assertThatThrownBy(() -> service.quote(USER_ID, new QuoteRequest(order.getId())))
        .isInstanceOf(com.example.mealprep.grocery.exception.ProviderUnavailableException.class);
    verify(failureRecorder)
        .recordProviderUnavailable(order.getId(), "login_required", "session expired");
    verify(dataGateway, never()).saveOrder(any()); // no main-tx persist on failure path
    verify(eventPublisher, never()).publishEvent(any(GroceryOrderQuotedEvent.class));
  }

  @Test
  void quote_aiUnavailable_recordsDraftRevert_rethrows() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    when(provider.quote(any())).thenThrow(new AiUnavailableException("cost cap"));

    assertThatThrownBy(() -> service.quote(USER_ID, new QuoteRequest(order.getId())))
        .isInstanceOf(AiUnavailableException.class);
    verify(failureRecorder).recordAiUnavailableRevert(order.getId(), "cost cap");
    verify(eventPublisher, never()).publishEvent(any(GroceryOrderQuotedEvent.class));
  }

  @Test
  void quote_success_setsQuoted_writesPerLineObservation_publishesQuotedEvent() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    GroceryProviderState state = providerState(true);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake")).thenReturn(Optional.of(state));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    QuoteLineResult lr =
        new QuoteLineResult(OrderLineStatus.ADDED, "resolved-sku", 120, 2, "noted");
    when(provider.quote(any()))
        .thenReturn(new QuoteResult("prov-order-1", Map.of(ln.getId(), lr), 240, "GBP", NOW));

    service.quote(USER_ID, new QuoteRequest(order.getId()));

    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.QUOTED);
    assertThat(order.getProviderOrderId()).isEqualTo("prov-order-1");
    assertThat(order.getQuotedTotalPence()).isEqualTo(240);
    assertThat(order.getStatusReason()).isNull();
    assertThat(ln.getLineStatus()).isEqualTo(OrderLineStatus.ADDED);
    assertThat(ln.getProviderProductId()).isEqualTo("resolved-sku");
    assertThat(ln.getQuotedUnitPence()).isEqualTo(120);
    assertThat(ln.getPackCountRequested()).isEqualTo(2);
    assertThat(ln.getNote()).isEqualTo("noted");

    // Failure counter cleared on success.
    assertThat(state.getConsecutiveFailures()).isZero();
    assertThat(state.getLastFailureReason()).isNull();

    // One QUOTE observation written.
    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter).write(cmd.capture());
    assertThat(cmd.getValue().source()).isEqualTo(PriceSource.QUOTE);
    assertThat(cmd.getValue().paidTotalPence()).isEqualTo(120);

    // One quoted event, with the observations-written count.
    ArgumentCaptor<GroceryOrderQuotedEvent> evt =
        ArgumentCaptor.forClass(GroceryOrderQuotedEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().observationsWritten()).isEqualTo(1);
    assertThat(evt.getValue().quotedTotalPence()).isEqualTo(240);
  }

  @Test
  void quote_lineResultNull_isSkipped() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    // Empty lineResults — line gets no update.
    when(provider.quote(any())).thenReturn(new QuoteResult("po-1", Map.of(), 0, "GBP", NOW));

    service.quote(USER_ID, new QuoteRequest(order.getId()));

    assertThat(ln.getLineStatus()).isEqualTo(OrderLineStatus.QUEUED); // unchanged
    verify(priceObservationWriter, never()).write(any());
    ArgumentCaptor<GroceryOrderQuotedEvent> evt =
        ArgumentCaptor.forClass(GroceryOrderQuotedEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().observationsWritten()).isZero();
  }

  @Test
  void quote_lineWithNullQuotedUnitPence_noObservationWritten() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    QuoteLineResult lr = new QuoteLineResult(OrderLineStatus.ADDED, "resolved", null, 1, null);
    when(provider.quote(any()))
        .thenReturn(new QuoteResult("po-1", Map.of(ln.getId(), lr), 0, "GBP", NOW));

    service.quote(USER_ID, new QuoteRequest(order.getId()));

    verify(priceObservationWriter, never()).write(any());
    assertThat(ln.getProviderProductId()).isEqualTo("resolved");
    assertThat(ln.getLineStatus()).isEqualTo(OrderLineStatus.ADDED);
  }

  @Test
  void quote_currencyOnResult_overridesOrderDefault() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    when(provider.quote(any())).thenReturn(new QuoteResult("po-1", Map.of(), 0, "EUR", NOW));

    service.quote(USER_ID, new QuoteRequest(order.getId()));

    assertThat(order.getCurrency()).isEqualTo("EUR");
  }

  // ============================== placeOrder ==============================

  @Test
  void placeOrder_success_advancesToAwaitingUserConfirmation_notPlacedDirectly() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.QUOTED, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    when(provider.placeOrder(any()))
        .thenReturn(
            new PlaceOrderResult(
                "po-1",
                "https://confirm",
                Map.of(ln.getId(), OrderLineStatus.ADDED),
                false,
                List.of(),
                NOW));

    service.placeOrder(USER_ID, new PlaceOrderRequest(order.getId()));

    // Auto-advanced past PLACED → AWAITING_USER_CONFIRMATION (never auto-confirms).
    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.AWAITING_USER_CONFIRMATION);
    assertThat(order.getConfirmLink()).isEqualTo("https://confirm");
    assertThat(order.getProviderOrderId()).isEqualTo("po-1");
    assertThat(order.getPlacedAt()).isEqualTo(NOW);

    ArgumentCaptor<GroceryOrderPlacedEvent> evt =
        ArgumentCaptor.forClass(GroceryOrderPlacedEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().partial()).isFalse();
  }

  @Test
  void placeOrder_partialFailure_advancesViaPlacedPartial_emitsPartialTrue() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.QUOTED, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    PlaceOrderResult partial =
        new PlaceOrderResult(
            "po-1",
            "https://confirm-partial",
            Map.of(ln.getId(), OrderLineStatus.ADDED_PARTIAL),
            true,
            List.of(),
            NOW);
    when(provider.placeOrder(any()))
        .thenThrow(new ProviderPartialFailureException("partial", partial));

    service.placeOrder(USER_ID, new PlaceOrderRequest(order.getId()));

    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.AWAITING_USER_CONFIRMATION);
    assertThat(order.getConfirmLink()).isEqualTo("https://confirm-partial");
    ArgumentCaptor<GroceryOrderPlacedEvent> evt =
        ArgumentCaptor.forClass(GroceryOrderPlacedEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().partial()).isTrue();
  }

  @Test
  void placeOrder_providerUnavailable_recordsFailure_throws503() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.QUOTED, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    when(provider.placeOrder(any()))
        .thenThrow(new ProviderUnavailableException("fake", "provider_down", "down"));

    assertThatThrownBy(() -> service.placeOrder(USER_ID, new PlaceOrderRequest(order.getId())))
        .isInstanceOf(com.example.mealprep.grocery.exception.ProviderUnavailableException.class);
    verify(failureRecorder).recordProviderUnavailable(order.getId(), "provider_down", "down");
    verify(eventPublisher, never()).publishEvent(any(GroceryOrderPlacedEvent.class));
  }

  @Test
  void placeOrder_aiUnavailable_recordsRevert_rethrows() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.QUOTED, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    when(provider.placeOrder(any())).thenThrow(new AiUnavailableException("cap"));

    assertThatThrownBy(() -> service.placeOrder(USER_ID, new PlaceOrderRequest(order.getId())))
        .isInstanceOf(AiUnavailableException.class);
    verify(failureRecorder).recordAiUnavailableRevert(order.getId(), "cap");
    verify(eventPublisher, never()).publishEvent(any(GroceryOrderPlacedEvent.class));
  }

  // ============================== cancel ==============================

  @Test
  void cancel_setsCancelled_publishesCancelledEvent_callsProviderWhenProviderOrderIdPresent()
      throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.QUOTED, ln);
    order.setProviderOrderId("prov-order");
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    service.cancel(USER_ID, new CancelOrderRequest(order.getId(), "changed my mind"));

    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.CANCELLED);
    assertThat(order.getCancelledAt()).isEqualTo(NOW);
    assertThat(order.getCancelReason()).isEqualTo("changed my mind");
    assertThat(order.getStatusReason()).isNull();
    verify(provider).cancel("prov-order");
    ArgumentCaptor<GroceryOrderCancelledEvent> evt =
        ArgumentCaptor.forClass(GroceryOrderCancelledEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().reason()).isEqualTo("changed my mind");
  }

  @Test
  void cancel_noProviderOrderId_skipsProviderCancel() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    order.setProviderOrderId(null);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    service.cancel(USER_ID, new CancelOrderRequest(order.getId(), "early cancel"));

    verify(provider, never()).cancel(any());
    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.CANCELLED);
  }

  @Test
  void cancel_providerCancelFails_stillCancelsLocally_appendsFailureLog() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.QUOTED, ln);
    order.setProviderOrderId("po");
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    org.mockito.Mockito.doThrow(new ProviderUnavailableException("fake", "down", "msg"))
        .when(provider)
        .cancel("po");

    service.cancel(USER_ID, new CancelOrderRequest(order.getId(), "user wants out"));

    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.CANCELLED);
    assertThat(order.getAutomationFailureLog()).hasSize(1);
    assertThat(order.getAutomationFailureLog().get(0).step()).isEqualTo("cancel");
  }

  @Test
  void cancel_illegalEdge_fromReconciled_throws409() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED);
    GroceryOrder order = order(GroceryOrderStatus.RECONCILED, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    assertThatThrownBy(
            () -> service.cancel(USER_ID, new CancelOrderRequest(order.getId(), "too late")))
        .isInstanceOf(IllegalOrderTransitionException.class);
  }

  // ============================== markUserConfirmed ==============================

  @Test
  void markUserConfirmed_transitionsToConfirmed_publishesEvent() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.AWAITING_USER_CONFIRMATION, ln);
    order.setProviderOrderId("po-1");
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provider.checkStatus("po-1"))
        .thenReturn(
            new OrderStatus(
                GroceryOrderStatus.CONFIRMED, "ready", null, null, 250, null, List.of(), NOW));

    service.markUserConfirmed(USER_ID, order.getId());

    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.CONFIRMED);
    assertThat(order.getConfirmedAt()).isEqualTo(NOW);
    assertThat(order.getConfirmedTotalPence()).isEqualTo(250);
    ArgumentCaptor<GroceryOrderConfirmedEvent> evt =
        ArgumentCaptor.forClass(GroceryOrderConfirmedEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().confirmedTotalPence()).isEqualTo(250);
  }

  @Test
  void markUserConfirmed_providerCheckStatusFails_proceeds_logsAndConfirms() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.AWAITING_USER_CONFIRMATION, ln);
    order.setProviderOrderId("po-1");
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provider.checkStatus("po-1"))
        .thenThrow(new ProviderUnavailableException("fake", "down", "msg"));

    service.markUserConfirmed(USER_ID, order.getId());

    // Still transitions despite the provider check failure (best-effort confirm).
    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.CONFIRMED);
    verify(eventPublisher).publishEvent(any(GroceryOrderConfirmedEvent.class));
  }

  @Test
  void markUserConfirmed_noProviderOrderId_skipsProviderCall() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.AWAITING_USER_CONFIRMATION, ln);
    order.setProviderOrderId(null);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    service.markUserConfirmed(USER_ID, order.getId());

    verify(provider, never()).checkStatus(any());
    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.CONFIRMED);
  }

  // ============================== markDelivered ==============================

  @Test
  void markDelivered_transitionsToDelivered_publishesDeliveredEvent_callsTryReconcile() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED);
    GroceryOrder order = order(GroceryOrderStatus.CONFIRMED, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(substitutionPersister.persistAll(any(), any())).thenReturn(List.of());

    service.markDelivered(USER_ID, order.getId());

    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.DELIVERED);
    assertThat(order.getDeliveredAt()).isEqualTo(NOW);
    verify(orderReconciler).tryReconcile(order.getId());
    ArgumentCaptor<GroceryOrderDeliveredEvent> evt =
        ArgumentCaptor.forClass(GroceryOrderDeliveredEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().outstandingProposalsCount()).isZero();
  }

  @Test
  void markDelivered_outstandingProposalsCount_isPersistedSize() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED);
    GroceryOrder order = order(GroceryOrderStatus.CONFIRMED, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(substitutionPersister.persistAll(any(), any()))
        .thenReturn(
            List.of(
                GroceryTestData.substitutionProposal().build(),
                GroceryTestData.substitutionProposal().build(),
                GroceryTestData.substitutionProposal().build()));

    service.markDelivered(USER_ID, order.getId());

    ArgumentCaptor<GroceryOrderDeliveredEvent> evt =
        ArgumentCaptor.forClass(GroceryOrderDeliveredEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().outstandingProposalsCount()).isEqualTo(3);
  }

  @Test
  void markDelivered_illegalEdge_fromDraft_throws409() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> service.markDelivered(USER_ID, order.getId()))
        .isInstanceOf(IllegalOrderTransitionException.class);
    verifyNoInteractions(substitutionPersister);
  }

  // ============================== refreshStatus ==============================

  @Test
  void refreshStatus_noProviderOrderId_returnsReloaded_noProviderCall() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.PLACED, ln);
    order.setProviderOrderId(null);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    service.refreshStatus(USER_ID, order.getId());

    verify(provider, never()).checkStatus(any());
    verify(eventPublisher, never()).publishEvent(any(GroceryOrderDeliveredEvent.class));
  }

  @Test
  void refreshStatus_providerUnavailable_throws503_noStateChange() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.PLACED, ln);
    order.setProviderOrderId("po-1");
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provider.checkStatus("po-1"))
        .thenThrow(new ProviderUnavailableException("fake", "down", "msg"));

    assertThatThrownBy(() -> service.refreshStatus(USER_ID, order.getId()))
        .isInstanceOf(com.example.mealprep.grocery.exception.ProviderUnavailableException.class);
    // The order state never changed.
    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.PLACED);
  }

  @Test
  void refreshStatus_deliveryReportedAndConfirmed_advancesToDelivered_callsTryReconcile()
      throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.CONFIRMED, ln);
    order.setProviderOrderId("po-1");
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provider.checkStatus("po-1"))
        .thenReturn(
            new OrderStatus(
                GroceryOrderStatus.DELIVERED, "delivered", null, null, null, null, List.of(), NOW));
    when(substitutionPersister.persistAll(any(), any())).thenReturn(List.of());

    service.refreshStatus(USER_ID, order.getId());

    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.DELIVERED);
    assertThat(order.getLastStatusCheckAt()).isEqualTo(NOW);
    verify(orderReconciler).tryReconcile(order.getId());
    verify(eventPublisher).publishEvent(any(GroceryOrderDeliveredEvent.class));
  }

  @Test
  void refreshStatus_deliveryReportedButCurrentStateNotConfirmed_doesNotAdvance() throws Exception {
    // DRAFT → DELIVERED is illegal; the canTransition gate keeps the state at DRAFT.
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    order.setProviderOrderId("po-1");
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provider.checkStatus("po-1"))
        .thenReturn(
            new OrderStatus(
                GroceryOrderStatus.DELIVERED, "delivered", null, null, null, null, List.of(), NOW));

    service.refreshStatus(USER_ID, order.getId());

    // Did NOT auto-transition.
    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.DRAFT);
    verify(orderReconciler, never()).tryReconcile(any());
    verify(eventPublisher, never()).publishEvent(any(GroceryOrderDeliveredEvent.class));
    // Status totals + lastStatusCheckAt still updated.
    assertThat(order.getLastStatusCheckAt()).isEqualTo(NOW);
    verify(dataGateway).saveOrder(order); // the no-advance branch saves on the else-arm
  }

  // ============================== resolveSubstitution ==============================

  @Test
  void resolveSubstitution_decisionPending_throws409() {
    GrocerySubstitutionProposal proposal = GroceryTestData.substitutionProposal().build();
    ResolveSubstitutionRequest req =
        new ResolveSubstitutionRequest(
            proposal.getId(), SubstitutionProposalStatus.PENDING_USER_REVIEW);

    assertThatThrownBy(() -> service.resolveSubstitution(USER_ID, req))
        .isInstanceOf(IllegalSubstitutionStateException.class);
    verify(dataGateway, never()).findProposalById(any());
  }

  @Test
  void resolveSubstitution_decisionUnparsed_throws409() {
    GrocerySubstitutionProposal proposal = GroceryTestData.substitutionProposal().build();
    ResolveSubstitutionRequest req =
        new ResolveSubstitutionRequest(proposal.getId(), SubstitutionProposalStatus.UNPARSED);

    assertThatThrownBy(() -> service.resolveSubstitution(USER_ID, req))
        .isInstanceOf(IllegalSubstitutionStateException.class);
  }

  @Test
  void resolveSubstitution_proposalNotFound_throws404() {
    UUID id = UUID.randomUUID();
    when(dataGateway.findProposalById(id)).thenReturn(Optional.empty());
    ResolveSubstitutionRequest req =
        new ResolveSubstitutionRequest(id, SubstitutionProposalStatus.ACCEPTED);

    assertThatThrownBy(() -> service.resolveSubstitution(USER_ID, req))
        .isInstanceOf(GrocerySubstitutionProposalNotFoundException.class);
  }

  @Test
  void resolveSubstitution_alreadyAccepted_throws409_cannotReresolve() {
    GrocerySubstitutionProposal proposal =
        GroceryTestData.substitutionProposal()
            .proposalStatus(SubstitutionProposalStatus.ACCEPTED)
            .build();
    when(dataGateway.findProposalById(proposal.getId())).thenReturn(Optional.of(proposal));

    assertThatThrownBy(
            () ->
                service.resolveSubstitution(
                    USER_ID,
                    new ResolveSubstitutionRequest(
                        proposal.getId(), SubstitutionProposalStatus.REJECTED)))
        .isInstanceOf(IllegalSubstitutionStateException.class);
  }

  @Test
  void resolveSubstitution_alreadyRejected_throws409() {
    GrocerySubstitutionProposal proposal =
        GroceryTestData.substitutionProposal()
            .proposalStatus(SubstitutionProposalStatus.REJECTED)
            .build();
    when(dataGateway.findProposalById(proposal.getId())).thenReturn(Optional.of(proposal));

    assertThatThrownBy(
            () ->
                service.resolveSubstitution(
                    USER_ID,
                    new ResolveSubstitutionRequest(
                        proposal.getId(), SubstitutionProposalStatus.ACCEPTED)))
        .isInstanceOf(IllegalSubstitutionStateException.class);
  }

  @Test
  void resolveSubstitution_pendingToAccepted_setsStatus_callsTryReconcile() {
    GrocerySubstitutionProposal proposal =
        GroceryTestData.substitutionProposal()
            .proposalStatus(SubstitutionProposalStatus.PENDING_USER_REVIEW)
            .build();
    when(dataGateway.findProposalById(proposal.getId())).thenReturn(Optional.of(proposal));
    when(dataGateway.saveAndFlushProposal(any())).thenAnswer(inv -> inv.getArgument(0));

    service.resolveSubstitution(
        USER_ID,
        new ResolveSubstitutionRequest(proposal.getId(), SubstitutionProposalStatus.ACCEPTED));

    assertThat(proposal.getProposalStatus()).isEqualTo(SubstitutionProposalStatus.ACCEPTED);
    assertThat(proposal.getResolvedAt()).isEqualTo(NOW);
    assertThat(proposal.getResolvedByUserId()).isEqualTo(USER_ID);
    verify(orderReconciler).tryReconcile(proposal.getGroceryOrderId());
  }

  @Test
  void resolveSubstitution_unparsedToRejected_isLegal() {
    GrocerySubstitutionProposal proposal =
        GroceryTestData.substitutionProposal()
            .proposalStatus(SubstitutionProposalStatus.UNPARSED)
            .build();
    when(dataGateway.findProposalById(proposal.getId())).thenReturn(Optional.of(proposal));
    when(dataGateway.saveAndFlushProposal(any())).thenAnswer(inv -> inv.getArgument(0));

    service.resolveSubstitution(
        USER_ID,
        new ResolveSubstitutionRequest(proposal.getId(), SubstitutionProposalStatus.REJECTED));

    assertThat(proposal.getProposalStatus()).isEqualTo(SubstitutionProposalStatus.REJECTED);
    verify(orderReconciler).tryReconcile(proposal.getGroceryOrderId());
  }

  // ============================== upsertProviderConnection ==============================

  @Test
  void upsertProviderConnection_newState_createsRowWithFailureCounterCleared() {
    when(dataGateway.findProviderState(USER_ID, "fake")).thenReturn(Optional.empty());
    when(dataGateway.saveProviderState(any())).thenAnswer(inv -> inv.getArgument(0));

    service.upsertProviderConnection(
        USER_ID, new ProviderConnectionRequest("fake", true, true, 15));

    ArgumentCaptor<GroceryProviderState> captor =
        ArgumentCaptor.forClass(GroceryProviderState.class);
    verify(dataGateway).saveProviderState(captor.capture());
    GroceryProviderState saved = captor.getValue();
    assertThat(saved.isEnabled()).isTrue();
    assertThat(saved.isScheduledRefreshEnabled()).isTrue();
    assertThat(saved.getRefreshTopNIngredients()).isEqualTo(15);
    assertThat(saved.getConsecutiveFailures()).isZero();
    assertThat(saved.getLastFailureReason()).isNull();
  }

  @Test
  void upsertProviderConnection_existingState_clearsFailureCounter_butKeepsTopN_whenRequestNull() {
    GroceryProviderState existing = providerState(true);
    existing.setRefreshTopNIngredients(20);
    existing.setConsecutiveFailures(5);
    existing.setLastFailureReason("something bad");
    when(dataGateway.findProviderState(USER_ID, "fake")).thenReturn(Optional.of(existing));
    when(dataGateway.saveProviderState(any())).thenAnswer(inv -> inv.getArgument(0));

    service.upsertProviderConnection(
        USER_ID, new ProviderConnectionRequest("fake", true, false, null));

    assertThat(existing.getConsecutiveFailures()).isZero();
    assertThat(existing.getLastFailureReason()).isNull();
    assertThat(existing.getRefreshTopNIngredients()).isEqualTo(20); // unchanged (request had null)
    assertThat(existing.isScheduledRefreshEnabled()).isFalse();
  }

  @Test
  void getProviderState_missing_throws422() {
    when(dataGateway.findProviderState(USER_ID, "fake")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getProviderState(USER_ID, "fake"))
        .isInstanceOf(ProviderNotConfiguredException.class);
  }

  // ============================== mutation-hardening (DTO non-null + side effects)
  // ==============================

  @Test
  void createDraft_returnsNonNullDtoFromReload() {
    ShoppingList list = shoppingList();
    when(dataGateway.findShoppingListWithLinesById(list.getId())).thenReturn(Optional.of(list));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(dataGateway.saveAndFlushOrder(any()))
        .thenAnswer(
            inv -> {
              GroceryOrder saved = inv.getArgument(0, GroceryOrder.class);
              when(dataGateway.findOrderWithLinesById(saved.getId()))
                  .thenReturn(Optional.of(saved));
              return saved;
            });

    GroceryOrderDto result =
        service.createDraft(USER_ID, new CreateOrderRequest(list.getId(), "fake"));

    // Pin NullReturnValsMutator survivors: the returned DTO MUST be non-null with content.
    assertThat(result).isNotNull();
    assertThat(result.status()).isEqualTo(GroceryOrderStatus.DRAFT);
    assertThat(result.providerKey()).isEqualTo("fake");
  }

  @Test
  void getById_present_returnsNonEmptyOptional() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    Optional<GroceryOrderDto> result = service.getById(order.getId());

    // Pin EmptyObjectReturnValsMutator survivor on getById line 153.
    assertThat(result).isPresent();
    assertThat(result.get().id()).isEqualTo(order.getId());
  }

  @Test
  void getById_missing_returnsEmptyOptional() {
    UUID id = UUID.randomUUID();
    when(dataGateway.findOrderWithLinesById(id)).thenReturn(Optional.empty());

    assertThat(service.getById(id)).isEmpty();
  }

  @Test
  void getByIds_nonEmpty_returnsAllMappedDtos() {
    GroceryOrder a = order(GroceryOrderStatus.DRAFT, line("flour", OrderLineStatus.QUEUED));
    GroceryOrder b = order(GroceryOrderStatus.DRAFT, line("rice", OrderLineStatus.QUEUED));
    when(dataGateway.findOrdersByIds(List.of(a.getId(), b.getId()))).thenReturn(List.of(a, b));

    List<GroceryOrderDto> result = service.getByIds(List.of(a.getId(), b.getId()));

    // Pin EmptyObjectReturnValsMutator survivor on getByIds line 168 — the result must contain
    // exactly the mapped DTOs, NOT an empty list.
    assertThat(result).hasSize(2);
    assertThat(result).extracting(GroceryOrderDto::id).containsExactly(a.getId(), b.getId());
  }

  @Test
  void getByIds_emptyInput_returnsEmptyList_noDataGatewayCall() {
    List<GroceryOrderDto> result = service.getByIds(List.of());
    assertThat(result).isEmpty();
    // No data-gateway invocation.
    verify(dataGateway, never()).findOrdersByIds(any());
  }

  @Test
  void getByIds_nullInput_returnsEmptyList() {
    // Pin the negated conditional on the `ids == null || ids.isEmpty()` guard.
    assertThat(service.getByIds(null)).isEmpty();
  }

  @Test
  void getOutstandingProposals_emptyOrder_returnsEmptyList_butNotNull() {
    UUID orderId = UUID.randomUUID();
    when(dataGateway.findProposalsByOrderIdAndStatus(any(UUID.class), any())).thenReturn(List.of());

    List<com.example.mealprep.grocery.api.dto.GrocerySubstitutionProposalDto> result =
        service.getOutstandingProposals(orderId);
    // EmptyObjectReturnValsMutator survivor — must be empty (not null), but specifically the
    // CONCRETE empty list, not the mutated default.
    assertThat(result).isNotNull().isEmpty();
  }

  @Test
  void cancel_returnsNonNullDto_andSetsAllFields() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    GroceryOrderDto result =
        service.cancel(USER_ID, new CancelOrderRequest(order.getId(), "reason"));

    // Pin NullReturnValsMutator survivor on cancel line 554.
    assertThat(result).isNotNull();
    assertThat(result.status()).isEqualTo(GroceryOrderStatus.CANCELLED);
    assertThat(order.getCancelReason()).isEqualTo("reason");
  }

  @Test
  void markDelivered_returnsNonNullDto_afterAdvance() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED);
    GroceryOrder order = order(GroceryOrderStatus.CONFIRMED, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(substitutionPersister.persistAll(any(), any())).thenReturn(List.of());

    GroceryOrderDto result = service.markDelivered(USER_ID, order.getId());
    assertThat(result).isNotNull();
    assertThat(result.status()).isEqualTo(GroceryOrderStatus.DELIVERED);
  }

  @Test
  void markUserConfirmed_returnsNonNullDto_afterConfirm() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.AWAITING_USER_CONFIRMATION, ln);
    order.setProviderOrderId(null);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    GroceryOrderDto result = service.markUserConfirmed(USER_ID, order.getId());
    assertThat(result).isNotNull();
    assertThat(result.status()).isEqualTo(GroceryOrderStatus.CONFIRMED);
  }

  @Test
  void refreshStatus_returnsNonNullDto_evenWhenNoProviderOrderId() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.PLACED, ln);
    order.setProviderOrderId(null);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    GroceryOrderDto result = service.refreshStatus(USER_ID, order.getId());
    assertThat(result).isNotNull();
  }

  @Test
  void resolveSubstitution_pendingToAccepted_returnsNonNullDto() {
    GrocerySubstitutionProposal proposal =
        GroceryTestData.substitutionProposal()
            .proposalStatus(SubstitutionProposalStatus.PENDING_USER_REVIEW)
            .build();
    when(dataGateway.findProposalById(proposal.getId())).thenReturn(Optional.of(proposal));
    when(dataGateway.saveAndFlushProposal(any())).thenAnswer(inv -> inv.getArgument(0));
    com.example.mealprep.grocery.api.dto.GrocerySubstitutionProposalDto mapped =
        org.mockito.Mockito.mock(
            com.example.mealprep.grocery.api.dto.GrocerySubstitutionProposalDto.class);
    when(proposalMapper.toDto(any())).thenReturn(mapped);

    com.example.mealprep.grocery.api.dto.GrocerySubstitutionProposalDto result =
        service.resolveSubstitution(
            USER_ID,
            new ResolveSubstitutionRequest(proposal.getId(), SubstitutionProposalStatus.ACCEPTED));

    // Pin NullReturnValsMutator on resolveSubstitution line 623.
    assertThat(result).isNotNull();
  }

  @Test
  void upsertProviderConnection_returnsNonNullDto() {
    when(dataGateway.findProviderState(USER_ID, "fake")).thenReturn(Optional.empty());
    when(dataGateway.saveProviderState(any())).thenAnswer(inv -> inv.getArgument(0));
    com.example.mealprep.grocery.api.dto.GroceryProviderStateDto mapped =
        org.mockito.Mockito.mock(
            com.example.mealprep.grocery.api.dto.GroceryProviderStateDto.class);
    when(providerStateMapper.toDto(any())).thenReturn(mapped);

    com.example.mealprep.grocery.api.dto.GroceryProviderStateDto result =
        service.upsertProviderConnection(
            USER_ID, new ProviderConnectionRequest("fake", true, true, 15));
    assertThat(result).isNotNull();
  }

  @Test
  void getProviderState_returnsNonNullDto() {
    GroceryProviderState state = providerState(true);
    when(dataGateway.findProviderState(USER_ID, "fake")).thenReturn(Optional.of(state));
    com.example.mealprep.grocery.api.dto.GroceryProviderStateDto mapped =
        org.mockito.Mockito.mock(
            com.example.mealprep.grocery.api.dto.GroceryProviderStateDto.class);
    when(providerStateMapper.toDto(state)).thenReturn(mapped);

    com.example.mealprep.grocery.api.dto.GroceryProviderStateDto result =
        service.getProviderState(USER_ID, "fake");
    assertThat(result).isNotNull();
  }

  @Test
  void quote_returnsNonNullDto_andSaveOrderCalled() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    when(provider.quote(any())).thenReturn(new QuoteResult("po-1", Map.of(), 0, "GBP", NOW));

    GroceryOrderDto result = service.quote(USER_ID, new QuoteRequest(order.getId()));
    assertThat(result).isNotNull();
    verify(dataGateway).saveOrder(order);
    verify(dataGateway).saveProviderState(any());
  }

  @Test
  void placeOrder_returnsNonNullDto_andSetsConfirmLink_andSavesProviderState() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.QUOTED, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    when(provider.placeOrder(any()))
        .thenReturn(
            new PlaceOrderResult(
                "po-1",
                "https://confirm.example",
                Map.of(),
                false,
                List.of(),
                NOW.minus(60, java.time.temporal.ChronoUnit.SECONDS)));

    GroceryOrderDto result = service.placeOrder(USER_ID, new PlaceOrderRequest(order.getId()));

    // Pin NullReturnValsMutator + multiple VoidMethodCall survivors on placeOrder.
    assertThat(result).isNotNull();
    assertThat(order.getConfirmLink()).isEqualTo("https://confirm.example");
    assertThat(order.getProviderOrderId()).isEqualTo("po-1");
    // placedAt uses the result's placedAt when non-null:
    assertThat(order.getPlacedAt()).isEqualTo(NOW.minus(60, java.time.temporal.ChronoUnit.SECONDS));
    verify(dataGateway).saveOrder(order);
    verify(dataGateway).saveProviderState(any());
  }

  @Test
  void placeOrder_resultPlacedAtNull_fallsBackToNow() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.QUOTED, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    when(provider.placeOrder(any()))
        .thenReturn(
            new PlaceOrderResult(
                "po-1", "https://confirm", Map.of(), false, List.of(), null)); // null placedAt

    service.placeOrder(USER_ID, new PlaceOrderRequest(order.getId()));

    assertThat(order.getPlacedAt()).isEqualTo(NOW);
  }

  @Test
  void placeOrder_failureLogEntries_appendedToOrder() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.QUOTED, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    com.example.mealprep.grocery.domain.entity.AutomationFailureRecord rec =
        new com.example.mealprep.grocery.domain.entity.AutomationFailureRecord(
            "place", "oops", NOW);
    when(provider.placeOrder(any()))
        .thenReturn(new PlaceOrderResult("po-1", "url", Map.of(), false, List.of(rec), NOW));

    service.placeOrder(USER_ID, new PlaceOrderRequest(order.getId()));

    assertThat(order.getAutomationFailureLog()).contains(rec);
  }

  @Test
  void placeOrder_emptyFailureLog_notAppended_lineStatusesNullTreatedAsEmpty() throws Exception {
    // The `if (result.failureLog() != null && !result.failureLog().isEmpty())` filter must
    // suppress appending when empty. And lineStatuses null → Map.of() fallback.
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.QUOTED, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    when(provider.placeOrder(any()))
        .thenReturn(new PlaceOrderResult("po-1", "url", null, false, List.of(), NOW));

    service.placeOrder(USER_ID, new PlaceOrderRequest(order.getId()));

    // Line status was NOT changed (no entry for it in null lineStatuses map).
    assertThat(ln.getLineStatus()).isEqualTo(OrderLineStatus.ADDED);
    assertThat(order.getAutomationFailureLog()).isEmpty();
  }

  @Test
  void applyStatusTotals_allFieldsCopied_whenStatusHasThem() throws Exception {
    // markUserConfirmed exercises applyStatusTotals via the provider.checkStatus path. Pin each
    // VoidMethodCallMutator on the setX calls + each NegateConditional on the null guards.
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.AWAITING_USER_CONFIRMATION, ln);
    order.setProviderOrderId("po-1");
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    Instant slotStart = NOW.minus(10, java.time.temporal.ChronoUnit.MINUTES);
    Instant slotEnd = NOW.plus(50, java.time.temporal.ChronoUnit.MINUTES);
    when(provider.checkStatus("po-1"))
        .thenReturn(
            new OrderStatus(
                GroceryOrderStatus.CONFIRMED,
                "ready",
                slotStart,
                slotEnd,
                250,
                300,
                List.of(),
                NOW));

    service.markUserConfirmed(USER_ID, order.getId());

    assertThat(order.getDeliverySlotStart()).isEqualTo(slotStart);
    assertThat(order.getDeliverySlotEnd()).isEqualTo(slotEnd);
    assertThat(order.getConfirmedTotalPence()).isEqualTo(250);
    assertThat(order.getPaidTotalPence()).isEqualTo(300);
  }

  @Test
  void applyStatusTotals_allNullFields_doesNotOverrideExistingValues() throws Exception {
    // When all status fields are null, the order's existing values are preserved (null-check
    // guards).
    GroceryOrderLine ln = line("flour", OrderLineStatus.ADDED);
    GroceryOrder order = order(GroceryOrderStatus.AWAITING_USER_CONFIRMATION, ln);
    order.setProviderOrderId("po-1");
    Instant existingStart = NOW.minus(1, java.time.temporal.ChronoUnit.HOURS);
    order.setDeliverySlotStart(existingStart);
    order.setConfirmedTotalPence(99);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provider.checkStatus("po-1"))
        .thenReturn(
            new OrderStatus(
                GroceryOrderStatus.CONFIRMED, "ready", null, null, null, null, List.of(), NOW));

    service.markUserConfirmed(USER_ID, order.getId());

    // Existing values preserved (all setX's were skipped due to null guards).
    assertThat(order.getDeliverySlotStart()).isEqualTo(existingStart);
    assertThat(order.getConfirmedTotalPence()).isEqualTo(99);
  }

  @Test
  void quote_writesProviderOrderId_andQuotedTotal_explicitly() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    when(provider.quote(any()))
        .thenReturn(new QuoteResult("PROV-XYZ", Map.of(), 12345, "USD", NOW));

    service.quote(USER_ID, new QuoteRequest(order.getId()));

    // Pin VoidMethodCallMutator on the setProviderOrderId / setQuotedTotalPence / setCurrency
    // calls in quote.
    assertThat(order.getProviderOrderId()).isEqualTo("PROV-XYZ");
    assertThat(order.getQuotedTotalPence()).isEqualTo(12345);
    assertThat(order.getCurrency()).isEqualTo("USD"); // overridden when provider supplied
  }

  @Test
  void writeQuoteObservation_householdNull_fallsBackToUserId() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    order.setHouseholdId(null); // null → falls back to userId on the observation
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    QuoteLineResult lr = new QuoteLineResult(OrderLineStatus.ADDED, "resolved-sku", 120, 2, null);
    when(provider.quote(any()))
        .thenReturn(new QuoteResult("po-1", Map.of(ln.getId(), lr), 240, null, NOW));

    service.quote(USER_ID, new QuoteRequest(order.getId()));

    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter).write(cmd.capture());
    assertThat(cmd.getValue().householdId()).isEqualTo(USER_ID);
    // packCountResolved supplied → that wins over line.packCountRequested.
    assertThat(cmd.getValue().packCount()).isEqualTo(2);
  }

  @Test
  void writeQuoteObservation_packCountResolvedNull_fallsBackToLinePackCount() throws Exception {
    GroceryOrderLine ln = line("flour", OrderLineStatus.QUEUED);
    GroceryOrder order = order(GroceryOrderStatus.DRAFT, ln);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(dataGateway.findProviderState(USER_ID, "fake"))
        .thenReturn(Optional.of(providerState(true)));
    when(basketDraftAssembler.assemble(order))
        .thenReturn(new BasketDraft(order.getId(), USER_ID, List.of(), null));
    QuoteLineResult lr =
        new QuoteLineResult(OrderLineStatus.ADDED, "resolved-sku", 120, null, null);
    when(provider.quote(any()))
        .thenReturn(new QuoteResult("po-1", Map.of(ln.getId(), lr), 240, "GBP", NOW));

    service.quote(USER_ID, new QuoteRequest(order.getId()));

    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter).write(cmd.capture());
    assertThat(cmd.getValue().packCount()).isEqualTo(2); // ln.packCountRequested fallback
  }

  // ============================== helpers ==============================

  private ShoppingList shoppingList() {
    ShoppingListLine ln =
        ShoppingListLine.builder()
            .id(UUID.randomUUID())
            .ingredientMappingKey("flour")
            .displayName("Flour")
            .requestedQuantity(new BigDecimal("1.000"))
            .requestedUnit("kg")
            .suggestedPackSizeG(1000)
            .suggestedPackCount(1)
            .lineType(ShoppingListLineType.PLANNED_DEMAND)
            .build();
    return ShoppingList.builder()
        .id(UUID.randomUUID())
        .userId(USER_ID)
        .householdId(UUID.randomUUID())
        .planId(UUID.randomUUID())
        .planGeneration(1)
        .estimatedTotalCurrency("GBP")
        .lines(new ArrayList<>(List.of(ln)))
        .build();
  }
}
