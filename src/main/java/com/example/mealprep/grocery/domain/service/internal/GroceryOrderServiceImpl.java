package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.core.lock.LockKey;
import com.example.mealprep.core.lock.LockService;
import com.example.mealprep.grocery.api.dto.CancelOrderRequest;
import com.example.mealprep.grocery.api.dto.CreateOrderRequest;
import com.example.mealprep.grocery.api.dto.GroceryOrderDto;
import com.example.mealprep.grocery.api.dto.GroceryProviderStateDto;
import com.example.mealprep.grocery.api.dto.GrocerySubstitutionProposalDto;
import com.example.mealprep.grocery.api.dto.PlaceOrderRequest;
import com.example.mealprep.grocery.api.dto.ProviderConnectionRequest;
import com.example.mealprep.grocery.api.dto.QuoteRequest;
import com.example.mealprep.grocery.api.dto.ResolveSubstitutionRequest;
import com.example.mealprep.grocery.api.mapper.GroceryOrderMapper;
import com.example.mealprep.grocery.api.mapper.GroceryProviderStateMapper;
import com.example.mealprep.grocery.config.GroceryConfig;
import com.example.mealprep.grocery.domain.entity.AutomationFailureRecord;
import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderLine;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.domain.entity.GroceryProviderState;
import com.example.mealprep.grocery.domain.entity.OrderLineStatus;
import com.example.mealprep.grocery.domain.entity.PriceSource;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.domain.service.GroceryOrderService;
import com.example.mealprep.grocery.domain.service.internal.providers.BasketDraft;
import com.example.mealprep.grocery.domain.service.internal.providers.GroceryProvider;
import com.example.mealprep.grocery.domain.service.internal.providers.OrderStatus;
import com.example.mealprep.grocery.domain.service.internal.providers.PlaceOrderResult;
import com.example.mealprep.grocery.domain.service.internal.providers.ProviderPartialFailureException;
import com.example.mealprep.grocery.domain.service.internal.providers.ProviderUnavailableException;
import com.example.mealprep.grocery.domain.service.internal.providers.QuoteLineResult;
import com.example.mealprep.grocery.domain.service.internal.providers.QuoteResult;
import com.example.mealprep.grocery.domain.service.internal.providers.SubstitutionProposal;
import com.example.mealprep.grocery.event.GroceryOrderCancelledEvent;
import com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent;
import com.example.mealprep.grocery.event.GroceryOrderDeliveredEvent;
import com.example.mealprep.grocery.event.GroceryOrderPlacedEvent;
import com.example.mealprep.grocery.event.GroceryOrderQuotedEvent;
import com.example.mealprep.grocery.event.GroceryProviderUnavailableEvent;
import com.example.mealprep.grocery.exception.GroceryOrderNotFoundException;
import com.example.mealprep.grocery.exception.OrderConcurrencyConflictException;
import com.example.mealprep.grocery.exception.ProviderNotConfiguredException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tier-3 order-lifecycle implementation (grocery-01e). Per lld/grocery.md §Flow 4 (lines 901-924),
 * §Flow 8 (lines 959-966), and the state machine (lines 780-796).
 *
 * <p>DIVERGENCE (ticket 01a): Tier 3 is a sibling impl, NOT folded into {@code GroceryServiceImpl},
 * because {@code GroceryOrderService.getById/getByIds} clash by erasure with the same-named Tier-1
 * methods. Both impls live in the {@code internal} package; Spring injects one bean per interface.
 *
 * <p><b>Single-flight.</b> {@code quote} / {@code placeOrder} / {@code refreshStatus} acquire a
 * Postgres advisory xact-lock keyed on {@code (userId, shoppingListId)} via {@code
 * core.LockService}; a failed acquire → {@link OrderConcurrencyConflictException} (409). The lock
 * is transaction-scoped (auto-released on commit/rollback), so the provider call runs inside the
 * same lock-holding tx (LLD line 980 — the lock spans the provider call AND the tx). The {@code
 * singleFlightLockTtlSeconds} config is the documented intent; the advisory lock's lifecycle is the
 * transaction.
 *
 * <p><b>Provider availability.</b> v1 ships ONLY the test-scoped {@code FakeGroceryProvider}; in
 * production there is no provider bean, so {@code provider(...)} returns empty and {@code quote} /
 * {@code placeOrder} surface {@code ProviderNotConfiguredException} (422). When the deferred Tesco
 * provider lands, it registers as a {@link GroceryProvider} bean keyed by {@code providerKey()}.
 */
@Service
public class GroceryOrderServiceImpl implements GroceryOrderService {

  private static final Logger log = LoggerFactory.getLogger(GroceryOrderServiceImpl.class);

  private static final String LOCK_SCOPE = "grocery-order";
  private static final String CURRENCY_GBP = "GBP";

  private final GroceryOrderDataGateway dataGateway;
  private final BasketDraftAssembler basketDraftAssembler;
  private final OrderStateMachine stateMachine;
  private final PriceObservationWriter priceObservationWriter;
  private final OrderFailureRecorder failureRecorder;
  private final GroceryOrderMapper orderMapper;
  private final GroceryProviderStateMapper providerStateMapper;
  private final LockService lockService;
  private final GroceryConfig groceryConfig;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectProvider<GroceryProvider> providers;
  private final Clock clock;

  public GroceryOrderServiceImpl(
      GroceryOrderDataGateway dataGateway,
      BasketDraftAssembler basketDraftAssembler,
      OrderStateMachine stateMachine,
      PriceObservationWriter priceObservationWriter,
      OrderFailureRecorder failureRecorder,
      GroceryOrderMapper orderMapper,
      GroceryProviderStateMapper providerStateMapper,
      LockService lockService,
      GroceryConfig groceryConfig,
      ApplicationEventPublisher eventPublisher,
      ObjectProvider<GroceryProvider> providers,
      Clock clock) {
    this.dataGateway = dataGateway;
    this.basketDraftAssembler = basketDraftAssembler;
    this.stateMachine = stateMachine;
    this.priceObservationWriter = priceObservationWriter;
    this.failureRecorder = failureRecorder;
    this.orderMapper = orderMapper;
    this.providerStateMapper = providerStateMapper;
    this.lockService = lockService;
    this.groceryConfig = groceryConfig;
    this.eventPublisher = eventPublisher;
    this.providers = providers;
    this.clock = clock;
  }

  // ---------------------------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public Optional<GroceryOrderDto> getById(UUID orderId) {
    return dataGateway.findOrderWithLinesById(orderId).map(orderMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<GroceryOrderDto> getByIds(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    List<GroceryOrderDto> out = new ArrayList<>(ids.size());
    for (GroceryOrder order : dataGateway.findOrdersByIds(ids)) {
      out.add(orderMapper.toDto(order));
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<GroceryOrderDto> getMyOrders(UUID userId, Pageable pageable) {
    return dataGateway.findMyOrders(userId, pageable).map(orderMapper::toDto);
  }

  // ---------------------------------------------------------------------------------------------
  // Lifecycle (Flow 4)
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code createDraft} (→ {@code DRAFT}). Validates the shopping list (404) + provider config (422
   * if no/disabled provider state); deep-copies lines from the list (the order is the snapshot, the
   * list may regenerate).
   */
  @Override
  @Transactional
  public GroceryOrderDto createDraft(UUID userId, CreateOrderRequest request) {
    ShoppingList list =
        dataGateway
            .findShoppingListWithLinesById(request.shoppingListId())
            .orElseThrow(() -> new GroceryOrderNotFoundException(request.shoppingListId()));

    String providerKey = request.providerKey();
    requireEnabledProviderState(userId, providerKey);

    Instant now = clock.instant();
    UUID orderId = UUID.randomUUID();
    GroceryOrder order =
        GroceryOrder.builder()
            .id(orderId)
            .userId(userId)
            .householdId(list.getHouseholdId())
            .shoppingListId(list.getId())
            .providerKey(providerKey)
            .status(GroceryOrderStatus.DRAFT)
            .currency(CURRENCY_GBP)
            .automationFailureLog(new ArrayList<>())
            .traceId(orderId)
            .lines(new ArrayList<>())
            .build();

    // Deep-copy each shopping-list line into an order line (the snapshot).
    for (ShoppingListLine src : list.getLines()) {
      GroceryOrderLine line =
          GroceryOrderLine.builder()
              .id(UUID.randomUUID())
              .groceryOrder(order)
              .shoppingListLineId(src.getId())
              .ingredientMappingKey(src.getIngredientMappingKey())
              .displayName(src.getDisplayName())
              .quantityRequested(src.getRequestedQuantity())
              .quantityUnit(src.getRequestedUnit())
              .packSizeG(src.getSuggestedPackSizeG())
              .packCountRequested(src.getSuggestedPackCount())
              .lineStatus(OrderLineStatus.QUEUED)
              .build();
      order.getLines().add(line);
    }

    GroceryOrder saved = dataGateway.saveAndFlushOrder(order);
    return reload(saved.getId());
  }

  /**
   * {@code quote} ({@code DRAFT → QUOTED}). Single-flight; load provider state (404/422); build the
   * basket; call {@code provider.quote}. On {@link ProviderUnavailableException} → {@code
   * PROVIDER_UNAVAILABLE} + {@link GroceryProviderUnavailableEvent} + 503. On {@link
   * AiUnavailableException} → revert to {@code DRAFT} + reason + 503. On success: write {@code
   * provider_order_id}, per-line quoted prices, one {@code QUOTE} observation per line (weight
   * 0.85), publish {@link GroceryOrderQuotedEvent} (the writer publishes the per-line {@code
   * PriceObservedEvent}).
   */
  @Override
  @Transactional
  public GroceryOrderDto quote(UUID userId, QuoteRequest request) {
    GroceryOrder order = loadOwnedOrderWithLines(userId, request.groceryOrderId());
    acquireSingleFlight(userId, order.getShoppingListId());
    stateMachine.assertCanTransition(order.getStatus(), GroceryOrderStatus.QUOTED);

    GroceryProviderState providerState =
        requireEnabledProviderState(userId, order.getProviderKey());
    GroceryProvider provider = requireProvider(order.getProviderKey());
    BasketDraft draft = basketDraftAssembler.assemble(order);

    QuoteResult result;
    try {
      result = provider.quote(draft);
    } catch (ProviderUnavailableException ex) {
      // Persist PROVIDER_UNAVAILABLE + event in a SEPARATE committed tx, then 503 (this tx rolls
      // back but has nothing left to persist; the advisory lock releases on rollback).
      failureRecorder.recordProviderUnavailable(order.getId(), ex.reason(), ex.getMessage());
      throw new com.example.mealprep.grocery.exception.ProviderUnavailableException(
          order.getProviderKey(), ex.reason(), ex.getMessage());
    } catch (AiUnavailableException ex) {
      // Persist the DRAFT revert + reason in a SEPARATE committed tx, then re-throw for 503.
      failureRecorder.recordAiUnavailableRevert(order.getId(), ex.getMessage());
      throw ex;
    }

    // Success — write provider order id + per-line quoted prices + QUOTE observations.
    Instant now = clock.instant();
    order.setProviderOrderId(result.providerOrderId());
    order.setQuotedTotalPence(result.quotedTotalPence());
    if (result.currency() != null) {
      order.setCurrency(result.currency());
    }

    int observationsWritten = 0;
    Map<UUID, QuoteLineResult> lineResults =
        result.lineResults() == null ? Map.of() : result.lineResults();
    for (GroceryOrderLine line : order.getLines()) {
      QuoteLineResult lr = lineResults.get(line.getId());
      if (lr == null) {
        continue;
      }
      if (lr.status() != null) {
        line.setLineStatus(lr.status());
      }
      if (lr.resolvedProviderProductId() != null) {
        line.setProviderProductId(lr.resolvedProviderProductId());
      }
      if (lr.packCountResolved() != null) {
        line.setPackCountRequested(lr.packCountResolved());
      }
      if (lr.note() != null) {
        line.setNote(lr.note());
      }
      if (lr.quotedUnitPence() != null) {
        line.setQuotedUnitPence(lr.quotedUnitPence());
        writeQuoteObservation(order, line, lr, now);
        observationsWritten++;
      }
    }

    clearFailure(providerState);
    order.setStatus(GroceryOrderStatus.QUOTED);
    order.setStatusReason(null);
    dataGateway.saveOrder(order);
    dataGateway.saveProviderState(providerState);

    eventPublisher.publishEvent(
        new GroceryOrderQuotedEvent(
            userId,
            order.getId(),
            order.getQuotedTotalPence(),
            observationsWritten,
            order.getTraceId(),
            now));
    return reload(order.getId());
  }

  /**
   * {@code placeOrder} ({@code QUOTED → PLACED | PLACED_PARTIAL}). Single-flight; call {@code
   * provider.placeOrder}; persist {@code confirm_link}, per-line statuses, failure log.
   * Auto-advance to {@code AWAITING_USER_CONFIRMATION} (never auto-confirms). On {@link
   * ProviderPartialFailureException} → {@code PLACED_PARTIAL} + persist added lines + confirm link
   * (200 body, NOT an error). On {@link ProviderUnavailableException} → {@code
   * PROVIDER_UNAVAILABLE} + event + 503. On {@link AiUnavailableException} → revert to {@code
   * DRAFT} + 503.
   */
  @Override
  @Transactional
  public GroceryOrderDto placeOrder(UUID userId, PlaceOrderRequest request) {
    GroceryOrder order = loadOwnedOrderWithLines(userId, request.groceryOrderId());
    acquireSingleFlight(userId, order.getShoppingListId());
    // QUOTED is the only legal source for either placed edge.
    stateMachine.assertCanTransition(order.getStatus(), GroceryOrderStatus.PLACED);

    GroceryProviderState providerState =
        requireEnabledProviderState(userId, order.getProviderKey());
    GroceryProvider provider = requireProvider(order.getProviderKey());
    BasketDraft draft = basketDraftAssembler.assemble(order);

    boolean partial = false;
    PlaceOrderResult result;
    try {
      result = provider.placeOrder(draft);
    } catch (ProviderPartialFailureException ex) {
      partial = true;
      result = ex.partialResult();
    } catch (ProviderUnavailableException ex) {
      failureRecorder.recordProviderUnavailable(order.getId(), ex.reason(), ex.getMessage());
      throw new com.example.mealprep.grocery.exception.ProviderUnavailableException(
          order.getProviderKey(), ex.reason(), ex.getMessage());
    } catch (AiUnavailableException ex) {
      failureRecorder.recordAiUnavailableRevert(order.getId(), ex.getMessage());
      throw ex;
    }

    Instant now = clock.instant();
    order.setProviderOrderId(result.providerOrderId());
    order.setConfirmLink(result.confirmLink());
    order.setPlacedAt(result.placedAt() != null ? result.placedAt() : now);

    Map<UUID, OrderLineStatus> lineStatuses =
        result.lineStatuses() == null ? Map.of() : result.lineStatuses();
    for (GroceryOrderLine line : order.getLines()) {
      OrderLineStatus s = lineStatuses.get(line.getId());
      if (s != null) {
        line.setLineStatus(s);
      }
    }
    if (result.failureLog() != null && !result.failureLog().isEmpty()) {
      order.getAutomationFailureLog().addAll(result.failureLog());
    }

    // Transition placed/placed_partial, then auto-advance to AWAITING_USER_CONFIRMATION.
    GroceryOrderStatus placedStatus =
        partial ? GroceryOrderStatus.PLACED_PARTIAL : GroceryOrderStatus.PLACED;
    stateMachine.assertCanTransition(order.getStatus(), placedStatus);
    order.setStatus(placedStatus);

    stateMachine.assertCanTransition(
        order.getStatus(), GroceryOrderStatus.AWAITING_USER_CONFIRMATION);
    order.setStatus(GroceryOrderStatus.AWAITING_USER_CONFIRMATION);
    order.setStatusReason(null);

    clearFailure(providerState);
    dataGateway.saveOrder(order);
    dataGateway.saveProviderState(providerState);

    eventPublisher.publishEvent(
        new GroceryOrderPlacedEvent(
            userId, order.getId(), order.getConfirmLink(), partial, order.getTraceId(), now));
    return reload(order.getId());
  }

  /**
   * {@code markUserConfirmed} ({@code AWAITING_USER_CONFIRMATION → CONFIRMED}). Optionally fetch
   * the confirmed total via {@code provider.checkStatus}; publish {@link
   * GroceryOrderConfirmedEvent} — the event Provisions consumes (the dormant {@code
   * GroceryOrderConfirmedListener}).
   */
  @Override
  @Transactional
  public GroceryOrderDto markUserConfirmed(UUID userId, UUID orderId) {
    GroceryOrder order = loadOwnedOrderWithLines(userId, orderId);
    stateMachine.assertCanTransition(order.getStatus(), GroceryOrderStatus.CONFIRMED);

    Instant now = clock.instant();
    Optional<GroceryProvider> provider = provider(order.getProviderKey());
    if (provider.isPresent() && order.getProviderOrderId() != null) {
      try {
        OrderStatus status = provider.get().checkStatus(order.getProviderOrderId());
        applyStatusTotals(order, status);
      } catch (ProviderUnavailableException ex) {
        // Best-effort: confirmation does not require a live provider read. Log and proceed.
        log.warn(
            "checkStatus failed during markUserConfirmed for order {} (provider {}): {}",
            order.getId(),
            order.getProviderKey(),
            ex.getMessage());
      }
    }

    order.setStatus(GroceryOrderStatus.CONFIRMED);
    order.setConfirmedAt(now);
    order.setStatusReason(null);
    dataGateway.saveOrder(order);

    eventPublisher.publishEvent(
        new GroceryOrderConfirmedEvent(
            userId,
            order.getId(),
            order.getConfirmedTotalPence(),
            order.getDeliverySlotStart(),
            order.getDeliverySlotEnd(),
            order.getTraceId(),
            now));
    return reload(order.getId());
  }

  /**
   * {@code refreshStatus}. Single-flight; wrap {@code provider.checkStatus} and apply lifecycle
   * implications. When the provider reports delivery, advance via {@code markDelivered}. On {@link
   * ProviderUnavailableException} → 503 (the order keeps its current state — a poll failure is not
   * a placement failure).
   */
  @Override
  @Transactional
  public GroceryOrderDto refreshStatus(UUID userId, UUID orderId) {
    GroceryOrder order = loadOwnedOrderWithLines(userId, orderId);
    acquireSingleFlight(userId, order.getShoppingListId());

    GroceryProvider provider = requireProvider(order.getProviderKey());
    if (order.getProviderOrderId() == null) {
      return reload(order.getId());
    }

    OrderStatus status;
    try {
      status = provider.checkStatus(order.getProviderOrderId());
    } catch (ProviderUnavailableException ex) {
      // Surface 503 via the unchecked, HTTP-mapped exception. No state change on a poll failure.
      throw new com.example.mealprep.grocery.exception.ProviderUnavailableException(
          order.getProviderKey(), ex.reason(), ex.getMessage());
    }

    Instant now = clock.instant();
    order.setLastStatusCheckAt(now);
    applyStatusTotals(order, status);

    // If the provider reports delivery and the order is confirmed, advance to DELIVERED.
    if (status.normalisedStatus() == GroceryOrderStatus.DELIVERED
        && stateMachine.canTransition(order.getStatus(), GroceryOrderStatus.DELIVERED)) {
      applyDelivered(order, status, now);
    } else {
      dataGateway.saveOrder(order);
    }
    return reload(order.getId());
  }

  /**
   * {@code markDelivered} ({@code CONFIRMED → DELIVERED}). Advance to {@code DELIVERED}; persist
   * any substitution proposals as {@code pending_user_review}; publish {@link
   * GroceryOrderDeliveredEvent}. Called both manually ("it arrived") and by 01g's scheduled poll.
   *
   * <p>01f SEAM: the proposal persistence is grocery-01f's {@code SubstitutionPersister} — NOT
   * built yet. 01e takes the smaller stub: it no-ops the proposal persistence and publishes only
   * {@code GroceryOrderDeliveredEvent} (count derived from the proposals the provider surfaced).
   * See the ticket's "01f is not built — stub its seam" note.
   */
  @Override
  @Transactional
  public GroceryOrderDto markDelivered(UUID userId, UUID orderId) {
    GroceryOrder order = loadOwnedOrderWithLines(userId, orderId);
    stateMachine.assertCanTransition(order.getStatus(), GroceryOrderStatus.DELIVERED);
    Instant now = clock.instant();
    // No provider read here — the caller (manual "it arrived" or 01g poll) already knows delivery.
    int outstanding = applyDelivered(order, null, now);
    eventPublisher.publishEvent(
        new GroceryOrderDeliveredEvent(
            userId, order.getId(), outstanding, order.getTraceId(), now));
    return reload(order.getId());
  }

  /**
   * {@code cancel} (any state up to {@code DELIVERED} → {@code CANCELLED}). Calls {@code
   * provider.cancel} when a {@code provider_order_id} exists; publishes {@link
   * GroceryOrderCancelledEvent}. After {@code RECONCILED} / {@code ARCHIVED} → {@code
   * IllegalOrderTransitionException} (409).
   */
  @Override
  @Transactional
  public GroceryOrderDto cancel(UUID userId, CancelOrderRequest request) {
    GroceryOrder order = loadOwnedOrderWithLines(userId, request.groceryOrderId());
    stateMachine.assertCanTransition(order.getStatus(), GroceryOrderStatus.CANCELLED);

    Optional<GroceryProvider> provider = provider(order.getProviderKey());
    if (provider.isPresent() && order.getProviderOrderId() != null) {
      try {
        provider.get().cancel(order.getProviderOrderId());
      } catch (ProviderUnavailableException ex) {
        // Cancellation is the user's intent — record the provider failure but still cancel locally.
        log.warn(
            "provider.cancel failed for order {} (provider {}): {}",
            order.getId(),
            order.getProviderKey(),
            ex.getMessage());
        order
            .getAutomationFailureLog()
            .add(new AutomationFailureRecord("cancel", ex.getMessage(), clock.instant()));
      }
    }

    Instant now = clock.instant();
    order.setStatus(GroceryOrderStatus.CANCELLED);
    order.setCancelledAt(now);
    order.setCancelReason(request.reason());
    order.setStatusReason(null);
    dataGateway.saveOrder(order);

    eventPublisher.publishEvent(
        new GroceryOrderCancelledEvent(
            userId, order.getId(), request.reason(), order.getTraceId(), now));
    return reload(order.getId());
  }

  // ---------------------------------------------------------------------------------------------
  // Substitutions — grocery-01f
  // ---------------------------------------------------------------------------------------------

  @Override
  @Transactional
  public GrocerySubstitutionProposalDto resolveSubstitution(
      UUID userId, ResolveSubstitutionRequest request) {
    throw new UnsupportedOperationException("implemented in grocery-01f");
  }

  @Override
  @Transactional(readOnly = true)
  public List<GrocerySubstitutionProposalDto> getOutstandingProposals(UUID orderId) {
    throw new UnsupportedOperationException("implemented in grocery-01f");
  }

  // ---------------------------------------------------------------------------------------------
  // Provider connection (Flow 8)
  // ---------------------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public GroceryProviderStateDto getProviderState(UUID userId, String providerKey) {
    GroceryProviderState state =
        dataGateway
            .findProviderState(userId, providerKey)
            .orElseThrow(() -> new ProviderNotConfiguredException(providerKey));
    return providerStateMapper.toDto(state);
  }

  @Override
  @Transactional
  public GroceryProviderStateDto upsertProviderConnection(
      UUID userId, ProviderConnectionRequest request) {
    GroceryProviderState state =
        dataGateway
            .findProviderState(userId, request.providerKey())
            .orElseGet(
                () ->
                    GroceryProviderState.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .providerKey(request.providerKey())
                        .consecutiveFailures(0)
                        .refreshTopNIngredients(groceryConfig.freshness().defaultRefreshTopN())
                        .build());

    state.setEnabled(request.enabled());
    state.setScheduledRefreshEnabled(request.scheduledRefreshEnabled());
    if (request.refreshTopNIngredients() != null) {
      state.setRefreshTopNIngredients(request.refreshTopNIngredients());
    }
    // Re-enabling / reconnecting resets the failure counter (Flow 8 — reset the failure counter).
    state.setConsecutiveFailures(0);
    state.setLastFailureReason(null);

    GroceryProviderState saved = dataGateway.saveProviderState(state);
    return providerStateMapper.toDto(saved);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private GroceryOrder loadOwnedOrderWithLines(UUID userId, UUID orderId) {
    GroceryOrder order =
        dataGateway
            .findOrderWithLinesById(orderId)
            .orElseThrow(() -> new GroceryOrderNotFoundException(orderId));
    if (!order.getUserId().equals(userId)) {
      // Owner mismatch is a 404 (don't leak existence to other users).
      throw new GroceryOrderNotFoundException(orderId);
    }
    return order;
  }

  /** Acquire the single-flight advisory lock; failed acquire → 409. */
  private void acquireSingleFlight(UUID userId, UUID shoppingListId) {
    UUID scopeId =
        new UUID(userId.getMostSignificantBits(), shoppingListId.getLeastSignificantBits());
    if (!lockService.tryAcquire(LockKey.forCustom(LOCK_SCOPE, scopeId))) {
      throw new OrderConcurrencyConflictException(
          "Another grocery operation is in progress for this list; retry shortly.");
    }
  }

  private GroceryProviderState requireEnabledProviderState(UUID userId, String providerKey) {
    GroceryProviderState state =
        dataGateway
            .findProviderState(userId, providerKey)
            .orElseThrow(() -> new ProviderNotConfiguredException(providerKey));
    if (!state.isEnabled()) {
      throw new ProviderNotConfiguredException(providerKey);
    }
    return state;
  }

  private Optional<GroceryProvider> provider(String providerKey) {
    return providers.stream().filter(p -> providerKey.equals(p.providerKey())).findFirst();
  }

  private GroceryProvider requireProvider(String providerKey) {
    return provider(providerKey).orElseThrow(() -> new ProviderNotConfiguredException(providerKey));
  }

  private void clearFailure(GroceryProviderState state) {
    state.setConsecutiveFailures(0);
    state.setLastFailureReason(null);
  }

  /** Write one {@code QUOTE} price observation (weight 0.85) for a quoted line via 01c's writer. */
  private void writeQuoteObservation(
      GroceryOrder order, GroceryOrderLine line, QuoteLineResult lr, Instant now) {
    Integer packCount =
        lr.packCountResolved() != null ? lr.packCountResolved() : line.getPackCountRequested();
    UUID householdId = order.getHouseholdId() != null ? order.getHouseholdId() : order.getUserId();
    priceObservationWriter.write(
        new PriceObservationWriter.WriteCommand(
            order.getUserId(),
            householdId,
            line.getIngredientMappingKey(),
            order.getProviderKey(),
            lr.resolvedProviderProductId(),
            line.getPackSizeG(),
            packCount,
            line.getQuantityRequested(),
            line.getQuantityUnit(),
            lr.quotedUnitPence(),
            order.getCurrency() != null ? order.getCurrency() : CURRENCY_GBP,
            PriceSource.QUOTE,
            order.getId(),
            line.getShoppingListLineId(),
            now,
            lr.note()));
  }

  /** Copy delivery-slot / total fields off a provider {@link OrderStatus} onto the order. */
  private void applyStatusTotals(GroceryOrder order, OrderStatus status) {
    if (status == null) {
      return;
    }
    if (status.deliverySlotStart() != null) {
      order.setDeliverySlotStart(status.deliverySlotStart());
    }
    if (status.deliverySlotEnd() != null) {
      order.setDeliverySlotEnd(status.deliverySlotEnd());
    }
    if (status.confirmedTotalPence() != null) {
      order.setConfirmedTotalPence(status.confirmedTotalPence());
    }
    if (status.paidTotalPence() != null) {
      order.setPaidTotalPence(status.paidTotalPence());
    }
  }

  /**
   * Advance an order to {@code DELIVERED} and stub the 01f substitution-persistence seam (no-op
   * persistence; only the proposal count is surfaced on the delivered event). Returns the
   * outstanding-proposal count.
   */
  private int applyDelivered(GroceryOrder order, OrderStatus status, Instant now) {
    applyStatusTotals(order, status);
    stateMachine.assertCanTransition(order.getStatus(), GroceryOrderStatus.DELIVERED);
    order.setStatus(GroceryOrderStatus.DELIVERED);
    order.setDeliveredAt(now);
    order.setStatusReason(null);
    dataGateway.saveOrder(order);

    // TODO(grocery-01f): persist provider-proposed substitutions as PENDING_USER_REVIEW via the
    // SubstitutionPersister + publish one SubstitutionProposedEvent each, then run tryReconcile.
    // 01e
    // stubs this: it surfaces the count only. The proposals are NOT persisted here.
    List<SubstitutionProposal> proposals =
        status == null || status.substitutions() == null ? List.of() : status.substitutions();
    return proposals.size();
  }

  private GroceryOrderDto reload(UUID orderId) {
    return dataGateway
        .findOrderWithLinesById(orderId)
        .map(orderMapper::toDto)
        .orElseThrow(() -> new GroceryOrderNotFoundException(orderId));
  }
}
