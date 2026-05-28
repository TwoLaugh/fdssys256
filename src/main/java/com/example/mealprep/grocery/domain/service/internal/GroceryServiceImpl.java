package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import com.example.mealprep.grocery.api.dto.BulkMarkBoughtRequest;
import com.example.mealprep.grocery.api.dto.ExportFormat;
import com.example.mealprep.grocery.api.dto.MarkBoughtRequest;
import com.example.mealprep.grocery.api.dto.MarkBoughtResultDto;
import com.example.mealprep.grocery.api.dto.PriceAggregateDto;
import com.example.mealprep.grocery.api.dto.PriceObservationDto;
import com.example.mealprep.grocery.api.dto.RecalculateShoppingListRequest;
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
import com.example.mealprep.grocery.domain.entity.PriceObservation;
import com.example.mealprep.grocery.domain.entity.PriceSource;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.domain.service.ManualFulfilmentService;
import com.example.mealprep.grocery.domain.service.PriceHistoryService;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource.ReferencePrice;
import com.example.mealprep.grocery.domain.service.ShoppingListService;
import com.example.mealprep.grocery.domain.service.internal.MarkBoughtInventoryBridge.BoughtLine;
import com.example.mealprep.grocery.domain.service.internal.providers.BasketDraft;
import com.example.mealprep.grocery.domain.service.internal.providers.BasketDraftLine;
import com.example.mealprep.grocery.domain.service.internal.providers.BasketDraftPreferences;
import com.example.mealprep.grocery.domain.service.internal.providers.GroceryProvider;
import com.example.mealprep.grocery.domain.service.internal.providers.ProviderUnavailableException;
import com.example.mealprep.grocery.domain.service.internal.providers.QuoteLineResult;
import com.example.mealprep.grocery.domain.service.internal.providers.QuoteResult;
import com.example.mealprep.grocery.event.ShoppingListBulkMarkedBoughtEvent;
import com.example.mealprep.grocery.event.ShoppingListGeneratedEvent;
import com.example.mealprep.grocery.event.ShoppingListItemMarkedBoughtEvent;
import com.example.mealprep.grocery.exception.LineAlreadyBoughtException;
import com.example.mealprep.grocery.exception.LineNotBoughtException;
import com.example.mealprep.grocery.exception.ShoppingListLineNotFoundException;
import com.example.mealprep.grocery.exception.ShoppingListNotFoundException;
import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.provisions.api.dto.GroceryImportResultDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skeleton implementation of the Tier-1 / Tier-2 / Tier-4 grocery service interfaces ({@link
 * ShoppingListService}, {@link ManualFulfilmentService}, {@link PriceHistoryService}). Per
 * lld/grocery.md lines 549-551 and ticket-01a §Service interfaces.
 *
 * <p>DIVERGENCE (ticket 01a): the LLD calls for ONE {@code GroceryServiceImpl} implementing all
 * FOUR interfaces. Java forbids it — {@code ShoppingListService.getById(UUID)} / {@code
 * getByIds(List&lt;UUID&gt;)} and {@code GroceryOrderService.getById(UUID)} / {@code
 * getByIds(List&lt;UUID&gt;)} have identical erasure but different return types (a same-signature
 * clash). 01a therefore splits Tier 3 into a sibling {@link GroceryOrderServiceImpl} in the same
 * {@code internal} package; the public interface contracts are unchanged and the {@code
 * GroceryModule} facade still injects one bean per interface. Worth user review.
 *
 * <p><b>01a ships ZERO behaviour</b> — every method throws {@link UnsupportedOperationException},
 * tagged with the tier ticket that fills it (01b / 01c / 01d / 01g). The bean registers so later
 * tickets' controllers can inject it and only fill bodies — never re-annotate. Read methods are
 * {@code @Transactional(readOnly = true)}; writes are {@code @Transactional}.
 */
@Service
public class GroceryServiceImpl
    implements ShoppingListService, ManualFulfilmentService, PriceHistoryService {

  private static final Logger log = LoggerFactory.getLogger(GroceryServiceImpl.class);

  // Tier-4 collaborators (01c). Tier 1/2 fields land with 01b/01d.
  private final PriceDataGateway priceDataGateway;
  private final PriceAggregator priceAggregator;
  private final PriceObservationWriter priceObservationWriter;
  private final ReferencePriceSource referencePriceSource;
  private final PriceObservationMapper priceObservationMapper;
  private final GroceryConfig groceryConfig;
  private final Clock clock;

  // Tier-1 collaborators (01b).
  private final ShoppingListDataGateway shoppingListDataGateway;
  private final ShoppingListCalculator shoppingListCalculator;
  private final ShoppingListExporter shoppingListExporter;
  private final ShoppingListMapper shoppingListMapper;
  private final PlanQueryService planQueryService;
  private final ApplicationEventPublisher eventPublisher;

  // Tier-2 collaborators (01d).
  private final MarkBoughtInventoryBridge markBoughtInventoryBridge;

  // Tier-4 scheduled-refresh collaborators (01g). All injected as ObjectProvider so the service
  // still constructs in unit / minimal-context scenarios that don't pull the full module graph.
  private final ObjectProvider<GroceryProvider> providers;
  private final ObjectProvider<RecipeQueryService> recipeQueryServiceProvider;
  private final ObjectProvider<PriceFreshnessGuardrails> guardrailsProvider;
  private final ObjectProvider<GroceryOrderDataGateway> orderGatewayProvider;

  public GroceryServiceImpl(
      PriceDataGateway priceDataGateway,
      PriceAggregator priceAggregator,
      PriceObservationWriter priceObservationWriter,
      ReferencePriceSource referencePriceSource,
      PriceObservationMapper priceObservationMapper,
      GroceryConfig groceryConfig,
      Clock clock,
      ShoppingListDataGateway shoppingListDataGateway,
      ShoppingListCalculator shoppingListCalculator,
      ShoppingListExporter shoppingListExporter,
      ShoppingListMapper shoppingListMapper,
      PlanQueryService planQueryService,
      ApplicationEventPublisher eventPublisher,
      MarkBoughtInventoryBridge markBoughtInventoryBridge,
      ObjectProvider<GroceryProvider> providers,
      ObjectProvider<RecipeQueryService> recipeQueryServiceProvider,
      ObjectProvider<PriceFreshnessGuardrails> guardrailsProvider,
      ObjectProvider<GroceryOrderDataGateway> orderGatewayProvider) {
    this.priceDataGateway = priceDataGateway;
    this.priceAggregator = priceAggregator;
    this.priceObservationWriter = priceObservationWriter;
    this.referencePriceSource = referencePriceSource;
    this.priceObservationMapper = priceObservationMapper;
    this.groceryConfig = groceryConfig;
    this.clock = clock;
    this.shoppingListDataGateway = shoppingListDataGateway;
    this.shoppingListCalculator = shoppingListCalculator;
    this.shoppingListExporter = shoppingListExporter;
    this.shoppingListMapper = shoppingListMapper;
    this.planQueryService = planQueryService;
    this.eventPublisher = eventPublisher;
    this.markBoughtInventoryBridge = markBoughtInventoryBridge;
    this.providers = providers;
    this.recipeQueryServiceProvider = recipeQueryServiceProvider;
    this.guardrailsProvider = guardrailsProvider;
    this.orderGatewayProvider = orderGatewayProvider;
  }

  // ---------------------------------------------------------------------------------------------
  // Tier 1 — ShoppingListService (implemented in grocery-01b)
  // ---------------------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public Optional<ShoppingListDto> getCurrentByPlanId(UUID planId) {
    return shoppingListDataGateway
        .findActiveByPlanId(planId)
        .map(this::loadWithLines)
        .map(shoppingListMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ShoppingListDto> getById(UUID shoppingListId) {
    return shoppingListDataGateway.findWithLinesById(shoppingListId).map(shoppingListMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ShoppingListDto> getByIds(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    List<ShoppingListDto> out = new ArrayList<>(ids.size());
    for (UUID id : ids) {
      shoppingListDataGateway
          .findWithLinesById(id)
          .map(shoppingListMapper::toDto)
          .ifPresent(out::add);
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ShoppingListDto> getHistory(UUID userId, Pageable pageable) {
    return shoppingListDataGateway
        .findHistoryByUserId(userId, pageable)
        .map(this::loadWithLines)
        .map(shoppingListMapper::toDto);
  }

  /**
   * Recalculate from a plan + provisions snapshot. Idempotent on {@code (planId, planGeneration)}:
   * the same generation returns the existing row; a new generation creates a new list and
   * supersedes the prior active one ({@code superseded_at = now()}). Concurrent same-generation
   * calls are serialised by {@code UNIQUE (plan_id, plan_generation)} — the loser catches {@link
   * DataIntegrityViolationException} and re-fetches. After commit publishes {@link
   * ShoppingListGeneratedEvent}. A missing plan / generation → 404.
   */
  @Override
  @Transactional
  public ShoppingListDto recalculate(UUID userId, RecalculateShoppingListRequest request) {
    PlanDto plan =
        planQueryService
            .getPlanById(request.planId())
            .orElseThrow(() -> new ShoppingListNotFoundException(request.planId()));
    int generation =
        request.planGeneration() != null ? request.planGeneration() : plan.generation();

    Optional<ShoppingList> existing =
        shoppingListDataGateway.findByPlanIdAndPlanGeneration(request.planId(), generation);
    if (existing.isPresent()) {
      return shoppingListMapper.toDto(loadWithLines(existing.get())); // idempotent
    }

    try {
      ShoppingList list = shoppingListCalculator.calculate(userId, plan, generation);
      supersedePrior(request.planId(), generation);
      ShoppingList saved = shoppingListDataGateway.saveAndFlush(list);
      eventPublisher.publishEvent(
          new ShoppingListGeneratedEvent(
              saved.getUserId(),
              saved.getHouseholdId(),
              saved.getId(),
              saved.getPlanId(),
              saved.getPlanGeneration(),
              saved.getLines() == null ? 0 : saved.getLines().size(),
              saved.getEstimatedTotalPence(),
              clock.instant()));
      return shoppingListMapper.toDto(saved);
    } catch (DataIntegrityViolationException race) {
      // Lost the UNIQUE (plan_id, plan_generation) race — re-fetch the winner.
      return shoppingListMapper.toDto(
          loadWithLines(
              shoppingListDataGateway
                  .findByPlanIdAndPlanGeneration(request.planId(), generation)
                  .orElseThrow(() -> race)));
    }
  }

  @Override
  @Transactional(readOnly = true)
  public ShoppingListExportDto export(UUID shoppingListId, ExportFormat format) {
    ShoppingList list =
        shoppingListDataGateway
            .findWithLinesById(shoppingListId)
            .orElseThrow(() -> new ShoppingListNotFoundException(shoppingListId));
    String content = shoppingListExporter.render(list, format);
    return new ShoppingListExportDto(shoppingListId, format, content);
  }

  // ---- Tier-1 helpers ----

  /** Set {@code superseded_at = now()} on the prior active list for a (different) generation. */
  private void supersedePrior(UUID planId, int newGeneration) {
    shoppingListDataGateway
        .findActiveByPlanId(planId)
        .filter(prior -> prior.getPlanGeneration() != newGeneration)
        .ifPresent(prior -> prior.setSupersededAt(clock.instant()));
  }

  /** Re-read with the lines entity-graph so the mapper sees the full aggregate (no N+1). */
  private ShoppingList loadWithLines(ShoppingList list) {
    return shoppingListDataGateway.findWithLinesById(list.getId()).orElse(list);
  }

  // ---------------------------------------------------------------------------------------------
  // Tier 2 — ManualFulfilmentService (implemented in grocery-01d)
  // ---------------------------------------------------------------------------------------------

  /**
   * Tier-2 single-line mark-bought (lld/grocery.md lines 877-885). Loads the line (404 if missing),
   * rejects an already-{@code BOUGHT} line (409), flips it to {@code BOUGHT} + {@code bought_*} +
   * {@code bought_via=MANUAL}, force-bumps the parent {@code @Version} (concurrent edit → 409),
   * writes a {@code MANUAL} price observation when a price is supplied (weight 0.7), adds inventory
   * via the canonical {@code applyGroceryOrder} (orderRef = line id → idempotent), and publishes
   * {@link ShoppingListItemMarkedBoughtEvent} (the writer publishes {@code PriceObservedEvent}
   * itself — not double-published here). Over-mark ({@code boughtQuantity > requestedQuantity}) is
   * ALLOWED — a {@code note} is set on the result, not a rejection (GROC-11).
   */
  @Override
  @Transactional
  public MarkBoughtResultDto markBought(UUID userId, MarkBoughtRequest request) {
    ShoppingListLine line =
        shoppingListDataGateway
            .findLineById(request.shoppingListLineId())
            .orElseThrow(() -> new ShoppingListLineNotFoundException(request.shoppingListLineId()));
    if (line.getFulfilmentStatus() == LineFulfilmentStatus.BOUGHT) {
      throw new LineAlreadyBoughtException(line.getId());
    }

    ShoppingList parent = line.getShoppingList();
    Instant boughtAt = request.boughtAt() != null ? request.boughtAt() : clock.instant();
    String note = overMarkNote(request.boughtQuantity(), line.getRequestedQuantity());

    applyBought(
        line,
        request.boughtQuantity(),
        request.boughtUnit(),
        request.boughtPricePence(),
        boughtAt,
        BoughtVia.MANUAL);
    shoppingListDataGateway.saveLine(line);
    shoppingListDataGateway.touchListVersion(parent);

    UUID priceObservationId = null;
    if (request.boughtPricePence() != null) {
      PriceObservation observation =
          writeManualObservation(
              userId,
              parent,
              line,
              request.store(),
              request.boughtQuantity(),
              request.boughtUnit(),
              request.boughtPricePence(),
              boughtAt,
              PriceSource.MANUAL,
              note);
      priceObservationId = observation.getId();
    }

    GroceryImportResultDto importResult =
        markBoughtInventoryBridge.applySingle(
            userId,
            request.store(),
            new BoughtLine(
                line, request.boughtQuantity(), request.boughtUnit(), request.boughtPricePence()),
            traceId(parent));
    UUID inventoryItemId =
        MarkBoughtInventoryBridge.firstInventoryItemId(importResult).orElse(null);

    eventPublisher.publishEvent(
        new ShoppingListItemMarkedBoughtEvent(
            userId,
            parent.getHouseholdId(),
            parent.getId(),
            line.getId(),
            line.getIngredientMappingKey(),
            request.boughtQuantity(),
            request.boughtUnit(),
            request.boughtPricePence(),
            BoughtVia.MANUAL,
            clock.instant()));

    return new MarkBoughtResultDto(
        line.getId(), LineFulfilmentStatus.BOUGHT, priceObservationId, inventoryItemId, note);
  }

  /**
   * Tier-2 bulk mark-bought (lld/grocery.md lines 889-899). With a {@code totalSpendPence}: split
   * proportionally by {@code estimated_line_pence} weight (uniform fallback share for no-estimate
   * lines), with the rounding residual dunned to the last/largest line so the parts sum EXACTLY to
   * the total; observations are {@code MANUAL_ESTIMATED} (weight 0.4). Without a total: each line
   * uses its own {@code estimated_unit_pence} if known, else no observation. ONE {@code
   * applyGroceryOrder} call (one provisions event) and ONE {@link
   * ShoppingListBulkMarkedBoughtEvent} — never per-line events. All lines must belong to {@code
   * request.shoppingListId()}; an unknown / cross-list / already-BOUGHT line → 404 / 409.
   */
  @Override
  @Transactional
  public List<MarkBoughtResultDto> bulkMarkBought(UUID userId, BulkMarkBoughtRequest request) {
    List<ShoppingListLine> lines = loadBulkLines(request);
    ShoppingList parent = lines.get(0).getShoppingList();
    Instant boughtAt = request.boughtAt() != null ? request.boughtAt() : clock.instant();

    Map<UUID, Integer> allocation =
        request.totalSpendPence() != null
            ? distribute(lines, request.totalSpendPence())
            : ownEstimate(lines);

    List<MarkBoughtResultDto> results = new ArrayList<>(lines.size());
    List<BoughtLine> boughtLines = new ArrayList<>(lines.size());
    PriceSource source =
        request.totalSpendPence() != null ? PriceSource.MANUAL_ESTIMATED : PriceSource.MANUAL;

    for (ShoppingListLine line : lines) {
      Integer pricePence = allocation.get(line.getId());
      BigDecimal quantity = effectiveBulkQuantity(line);
      String unit = effectiveBulkUnit(line);
      String note = overMarkNote(quantity, line.getRequestedQuantity());

      applyBought(line, quantity, unit, pricePence, boughtAt, BoughtVia.BULK_TOTAL);
      shoppingListDataGateway.saveLine(line);

      UUID priceObservationId = null;
      if (pricePence != null) {
        PriceObservation observation =
            writeManualObservation(
                userId,
                parent,
                line,
                request.store(),
                quantity,
                unit,
                pricePence,
                boughtAt,
                source,
                note);
        priceObservationId = observation.getId();
      }
      boughtLines.add(new BoughtLine(line, quantity, unit, pricePence));
      results.add(
          new MarkBoughtResultDto(
              line.getId(), LineFulfilmentStatus.BOUGHT, priceObservationId, null, note));
    }

    // One version bump on the aggregate root for the whole batch.
    shoppingListDataGateway.touchListVersion(parent);

    // ONE inventory call (one provisions event).
    GroceryImportResultDto importResult =
        markBoughtInventoryBridge.applyBulk(userId, request.store(), boughtLines, traceId(parent));
    // Best-effort: stamp the (single, when present) inventory id back onto each result.
    UUID inventoryItemId =
        MarkBoughtInventoryBridge.firstInventoryItemId(importResult).orElse(null);
    if (inventoryItemId != null) {
      for (int i = 0; i < results.size(); i++) {
        MarkBoughtResultDto r = results.get(i);
        results.set(
            i,
            new MarkBoughtResultDto(
                r.shoppingListLineId(),
                r.newStatus(),
                r.priceObservationId(),
                inventoryItemId,
                r.note()));
      }
    }

    // ONE bulk event (NOT per-line).
    eventPublisher.publishEvent(
        new ShoppingListBulkMarkedBoughtEvent(
            userId,
            parent.getHouseholdId(),
            parent.getId(),
            lines.stream().map(ShoppingListLine::getId).toList(),
            request.totalSpendPence(),
            clock.instant()));

    return results;
  }

  /**
   * Tier-2 undo (lld/grocery.md line 587 + 01d divergence). Reverses the GROCERY-side state only:
   * line → {@code UNFILLED}, {@code bought_*} cleared, and a COMPENSATING price note appended (the
   * append-only observation is NEVER deleted). There is no {@code reverseGroceryOrder} on the
   * shipped {@code ProvisionUpdateService} — undo therefore CANNOT cleanly reverse the inventory
   * add; it logs a WARN and the result carries the manual-correction caveat (the missing provisions
   * reverse API is flagged as a provisions follow-up). 404 missing / 409 not-{@code BOUGHT}.
   */
  @Override
  @Transactional
  public void undoMarkBought(UUID shoppingListLineId, UUID actorUserId) {
    ShoppingListLine line =
        shoppingListDataGateway
            .findLineById(shoppingListLineId)
            .orElseThrow(() -> new ShoppingListLineNotFoundException(shoppingListLineId));
    if (line.getFulfilmentStatus() != LineFulfilmentStatus.BOUGHT) {
      throw new LineNotBoughtException(shoppingListLineId, line.getFulfilmentStatus());
    }

    ShoppingList parent = line.getShoppingList();
    Instant now = clock.instant();

    // Compensating observation (append-only): a superseding MANUAL row with zero/null price and a
    // note that the prior mark-bought was undone. Never delete the original.
    if (line.getBoughtPricePence() != null) {
      writeManualObservation(
          actorUserId,
          parent,
          line,
          DEFAULT_MANUAL_STORE,
          line.getBoughtQuantity(),
          line.getBoughtUnit(),
          null, // no price on the compensating row
          now,
          PriceSource.MANUAL,
          "mark-bought undone for line " + line.getId() + "; supersedes the prior observation");
    }

    // Reverse grocery-side line state.
    line.setFulfilmentStatus(LineFulfilmentStatus.UNFILLED);
    line.setBoughtQuantity(null);
    line.setBoughtUnit(null);
    line.setBoughtPricePence(null);
    line.setBoughtAt(null);
    line.setBoughtVia(null);
    shoppingListDataGateway.saveLine(line);
    shoppingListDataGateway.touchListVersion(parent);

    // No provisions inventory-reverse API exists (verified against provisions-01h: there is no
    // reverseGroceryOrder / un-apply on ProvisionUpdateService). FLAG as a provisions follow-up;
    // inventory must be corrected manually.
    log.warn(
        "undoMarkBought reversed grocery-side state for line {} but the inventory add via"
            + " applyGroceryOrder CANNOT be reversed — no reverseGroceryOrder API on"
            + " ProvisionUpdateService (provisions follow-up). Inventory must be corrected manually.",
        line.getId());
  }

  // ---- Tier-2 helpers ----

  /** Store sentinel used by the compensating undo observation (matches the MANUAL default). */
  private static final String DEFAULT_MANUAL_STORE = "manual";

  /** Apply the bought-* mutation on a line (shared by single + bulk). */
  private void applyBought(
      ShoppingListLine line,
      BigDecimal boughtQuantity,
      String boughtUnit,
      Integer boughtPricePence,
      Instant boughtAt,
      BoughtVia via) {
    line.setFulfilmentStatus(LineFulfilmentStatus.BOUGHT);
    line.setBoughtQuantity(boughtQuantity);
    line.setBoughtUnit(boughtUnit);
    line.setBoughtPricePence(boughtPricePence);
    line.setBoughtAt(boughtAt);
    line.setBoughtVia(via);
  }

  /** A warning note when the bought quantity exceeds the requested one (over-mark, GROC-11). */
  private static String overMarkNote(BigDecimal bought, BigDecimal requested) {
    if (bought == null || requested == null) {
      return null;
    }
    if (bought.compareTo(requested) > 0) {
      return "bought quantity ("
          + bought.stripTrailingZeros().toPlainString()
          + ") exceeds requested ("
          + requested.stripTrailingZeros().toPlainString()
          + "); ad-hoc inventory recorded";
    }
    return null;
  }

  /** Write a MANUAL / MANUAL_ESTIMATED observation through the append-only writer. */
  private PriceObservation writeManualObservation(
      UUID userId,
      ShoppingList parent,
      ShoppingListLine line,
      String store,
      BigDecimal quantity,
      String unit,
      Integer pricePence,
      Instant observedAt,
      PriceSource source,
      String note) {
    UUID householdId = parent.getHouseholdId() != null ? parent.getHouseholdId() : userId;
    String resolvedStore = store != null && !store.isBlank() ? store : DEFAULT_MANUAL_STORE;
    return priceObservationWriter.write(
        new PriceObservationWriter.WriteCommand(
            userId,
            householdId,
            line.getIngredientMappingKey(),
            resolvedStore,
            null, // providerProductId
            line.getSuggestedPackSizeG(),
            null, // packCount
            quantity,
            unit,
            pricePence,
            "GBP",
            source,
            null, // groceryOrderId
            line.getId(),
            observedAt,
            note));
  }

  /**
   * Resolve + validate the lines for a bulk mark-bought (404 missing/cross-list; 409
   * already-bought).
   */
  private List<ShoppingListLine> loadBulkLines(BulkMarkBoughtRequest request) {
    List<UUID> ids = request.shoppingListLineIds();
    Map<UUID, ShoppingListLine> byId = new LinkedHashMap<>();
    for (ShoppingListLine l : shoppingListDataGateway.findLinesByIds(ids)) {
      byId.put(l.getId(), l);
    }
    List<ShoppingListLine> ordered = new ArrayList<>(ids.size());
    for (UUID id : ids) {
      ShoppingListLine l = byId.get(id);
      if (l == null || !request.shoppingListId().equals(l.getShoppingList().getId())) {
        throw new ShoppingListLineNotFoundException(id);
      }
      if (l.getFulfilmentStatus() == LineFulfilmentStatus.BOUGHT) {
        throw new LineAlreadyBoughtException(id);
      }
      ordered.add(l);
    }
    return ordered;
  }

  /**
   * Proportional total-spend distribution (lld/grocery.md line 892-893). Weight by {@code
   * estimated_line_pence}; lines with no estimate get a uniform fallback share of the residual; the
   * rounding residual is dunned to the last (largest-total) line so the parts sum EXACTLY to the
   * total.
   */
  private Map<UUID, Integer> distribute(List<ShoppingListLine> lines, int totalSpendPence) {
    long anchoredSum = 0L;
    int noEstimateCount = 0;
    for (ShoppingListLine l : lines) {
      Integer est = l.getEstimatedLinePence();
      if (est != null && est > 0) {
        anchoredSum += est;
      } else {
        noEstimateCount++;
      }
    }

    Map<UUID, Integer> allocation = new LinkedHashMap<>();
    long allocated = 0L;

    if (anchoredSum == 0L) {
      // No anchors at all (GG7 / GROC-10) — pure uniform split across all lines.
      int n = lines.size();
      for (int i = 0; i < n; i++) {
        int share = (int) (((long) totalSpendPence * (i + 1)) / n - allocated);
        allocation.put(lines.get(i).getId(), share);
        allocated += share;
      }
      return allocation;
    }

    // Reserve a uniform fallback pool for no-estimate lines: each gets the average anchored share.
    long fallbackEach =
        noEstimateCount == 0 ? 0L : Math.round((double) totalSpendPence / lines.size());
    long fallbackPool = fallbackEach * noEstimateCount;
    long anchoredBudget = Math.max(0L, (long) totalSpendPence - fallbackPool);

    UUID largestId = null;
    long largestWeight = -1L;
    for (ShoppingListLine l : lines) {
      Integer est = l.getEstimatedLinePence();
      int share;
      if (est != null && est > 0) {
        share = (int) Math.round((double) anchoredBudget * est / anchoredSum);
        if (est > largestWeight) {
          largestWeight = est;
          largestId = l.getId();
        }
      } else {
        share = (int) fallbackEach;
      }
      allocation.put(l.getId(), share);
      allocated += share;
    }

    // Dun the rounding residual to the largest anchored line (or the last line if all-fallback).
    UUID dunTarget = largestId != null ? largestId : lines.get(lines.size() - 1).getId();
    int residual = (int) (totalSpendPence - allocated);
    allocation.merge(dunTarget, residual, Integer::sum);
    return allocation;
  }

  /** No-total bulk: each line uses its own {@code estimated_unit_pence} if known, else no price. */
  private Map<UUID, Integer> ownEstimate(List<ShoppingListLine> lines) {
    Map<UUID, Integer> allocation = new LinkedHashMap<>();
    for (ShoppingListLine l : lines) {
      allocation.put(l.getId(), l.getEstimatedLinePence());
    }
    return allocation;
  }

  /** Bulk uses each line's requested quantity (the user marks the planned amount as bought). */
  private static BigDecimal effectiveBulkQuantity(ShoppingListLine line) {
    return line.getRequestedQuantity();
  }

  private static String effectiveBulkUnit(ShoppingListLine line) {
    return line.getRequestedUnit();
  }

  /** Reuse the list id as a deterministic trace id for the import (stable across retries). */
  private static UUID traceId(ShoppingList parent) {
    return parent.getId();
  }

  // ---------------------------------------------------------------------------------------------
  // Tier 4 — PriceHistoryService (implemented in grocery-01c / grocery-01d / grocery-01g)
  // ---------------------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public Optional<PriceAggregateDto> getAggregate(
      UUID householdId, String ingredientMappingKey, String store) {
    String key = IngredientMappingKeys.normalise(ingredientMappingKey);
    if (key == null || key.isEmpty()) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    Instant since = priceAggregator.windowStart(now);
    List<PriceObservation> rows =
        priceDataGateway.findRecentByKeyAcrossStores(householdId, key, since);
    if (store != null) {
      rows = filterByStore(rows, store);
    }
    return priceAggregator.aggregate(key, store, rows);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, PriceAggregateDto> getAggregatesByKeys(
      UUID householdId, Collection<String> keys) {
    // The ≤5-SQL target for 01b's step 6: ONE observation query + ONE reference batch.
    Set<String> normalised = new LinkedHashSet<>();
    for (String k : keys) {
      String n = IngredientMappingKeys.normalise(k);
      if (n != null && !n.isEmpty()) {
        normalised.add(n);
      }
    }
    Map<String, PriceAggregateDto> out = new LinkedHashMap<>();
    if (normalised.isEmpty()) {
      return out;
    }
    Instant now = clock.instant();
    Instant since = priceAggregator.windowStart(now);

    // SQL #1 — all in-window observations for all keys.
    List<PriceObservation> all = priceDataGateway.findRecentByKeys(householdId, normalised, since);
    Map<String, List<PriceObservation>> byKey = new LinkedHashMap<>();
    for (String k : normalised) {
      byKey.put(k, new ArrayList<>());
    }
    for (PriceObservation o : all) {
      byKey.computeIfAbsent(o.getIngredientMappingKey(), k -> new ArrayList<>()).add(o);
    }

    // SQL #2 — one reference batch for every key (used only as the cold-start fallback).
    Map<String, ReferencePrice> references = referencePriceSource.referencePrices(normalised);

    for (String k : normalised) {
      List<PriceObservation> rows = byKey.getOrDefault(k, List.of());
      Optional<PriceAggregateDto> agg;
      if (rows.isEmpty()) {
        agg = priceAggregator.toAggregate(k, null, references.get(k), now);
      } else {
        agg = priceAggregator.aggregate(k, null, rows);
      }
      agg.ifPresent(dto -> out.put(k, dto));
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public List<PriceAggregateDto> getCrossStoreAggregatesByKey(UUID householdId, String key) {
    String normalised = IngredientMappingKeys.normalise(key);
    if (normalised == null || normalised.isEmpty()) {
      return List.of();
    }
    Instant now = clock.instant();
    Instant since = priceAggregator.windowStart(now);
    List<PriceObservation> rows =
        priceDataGateway.findRecentByKeyAcrossStores(householdId, normalised, since);
    if (rows.isEmpty()) {
      // No observations in any store → a single reference (store=null) row, or empty when unmapped.
      return priceAggregator
          .referenceFallback(normalised, null, now)
          .map(List::of)
          .orElseGet(List::of);
    }
    // Distinct stores, insertion-ordered; one per-store aggregate each.
    Map<String, List<PriceObservation>> byStore = new LinkedHashMap<>();
    for (PriceObservation o : rows) {
      byStore.computeIfAbsent(o.getStore(), s -> new ArrayList<>()).add(o);
    }
    List<PriceAggregateDto> out = new ArrayList<>(byStore.size());
    for (Map.Entry<String, List<PriceObservation>> e : byStore.entrySet()) {
      priceAggregator.aggregate(normalised, e.getKey(), e.getValue()).ifPresent(out::add);
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<PriceObservationDto> getObservations(UUID userId, Pageable pageable) {
    return priceDataGateway
        .findObservationsByUser(userId, pageable)
        .map(priceObservationMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<PriceObservationDto> getObservationsByMappingKey(
      UUID householdId, String key, Pageable pageable) {
    String normalised = IngredientMappingKeys.normalise(key);
    if (normalised == null || normalised.isEmpty()) {
      return Page.empty(pageable);
    }
    return priceDataGateway
        .findObservationsByHouseholdKey(householdId, normalised, pageable)
        .map(priceObservationMapper::toDto);
  }

  @Override
  @Transactional
  public PriceObservationDto recordManualPrice(UUID userId, RecordManualPriceRequest request) {
    // The manual one-off REST entry — a MANUAL-source observation (weight 0.7). The mark-bought
    // path (01d) writes through PriceObservationWriter directly.
    PriceObservation saved =
        priceObservationWriter.write(
            new PriceObservationWriter.WriteCommand(
                userId,
                userId, // single-user mode: userId doubles as the household scope until configured
                request.ingredientMappingKey(),
                request.store(),
                null,
                null,
                null,
                request.quantity(),
                request.quantityUnit(),
                request.paidTotalPence(),
                "GBP",
                PriceSource.MANUAL,
                null,
                null,
                request.observedAt(),
                null));
    return priceObservationMapper.toDto(saved);
  }

  @Override
  @Transactional
  public RefreshPricesResultDto refreshOnDemand(UUID userId, RefreshPricesRequest request) {
    // useProviderQuote=false → return latest aggregates, no provider call (LLD line 945). The
    // useProviderQuote=true provider-quote leg depends on the GroceryProvider SPI (01e), which is
    // NOT built yet. Per the ticket, 01c must NOT hard-depend on 01e: with no provider available we
    // behave as useProviderQuote=false (observationsWritten=0). When 01e lands, the provider lookup
    // (ObjectProvider<GroceryProvider>) + AiUnavailableException handling wire in here.
    List<String> keys =
        request.ingredientMappingKeys() == null ? List.of() : request.ingredientMappingKeys();
    int refreshed =
        (int) keys.stream().map(IngredientMappingKeys::normalise).filter(this::nonBlank).count();
    return new RefreshPricesResultDto(0, refreshed, false, null);
  }

  /**
   * Body filled by grocery-01g (was a throwing stub from 01c). Per LLD §Flow 6 (lines 947-952).
   * Sequence for {@code userId}:
   *
   * <ol>
   *   <li>{@link PriceFreshnessGuardrails#preflight} with {@link
   *       PriceFreshnessGuardrails.RefreshKind#SCHEDULED}. {@code SKIP} / {@code BLOCK} → log INFO
   *       and return (the HLD's "scheduled refresh is the FIRST thing skipped when the cap is
   *       approached" — see lld/grocery.md line 953).
   *   <li>Resolve the user's provider connection ({@link
   *       GroceryOrderDataGateway#findProviderStatesByUserId}); skip silently when no provider
   *       state row exists or when {@code enabled = false} (a user with scheduled-refresh enabled
   *       but no live connection is a no-op rather than an error).
   *   <li>Resolve the top-N most-used ingredient keys via {@link
   *       RecipeQueryService#findUserRecipeIngredientKeys} — the LLD's {@code
   *       getTopUsedIngredientKeys(userId, lookbackWeeks=12, n)} is NOT shipped on the recipe
   *       module today, so this method falls back to "count occurrences across every active
   *       recipe's current version" with no lookback window. Documented as a divergence in the 01g
   *       PR body. Empty key set → no-op return.
   *   <li>Build a synthetic {@link BasketDraft} from the keys and call {@code provider.quote}.
   *       Write one {@link PriceSource#QUOTE} observation per quoted line via {@link
   *       PriceObservationWriter}. Failures degrade per the standard provider-exception contract:
   *       {@link ProviderUnavailableException} → log + return (a probe failure is not a placement
   *       failure); {@link AiUnavailableException} → log + return (the cap will already have
   *       skipped scheduled work via the listener path on the next cap-exceeded event).
   * </ol>
   *
   * <p>This is the {@code @Async}-per-user worker the {@code GroceryScheduledJobs} weekly driver
   * dispatches into. Each call is its own bounded transaction (LLD line 981) — the surrounding
   * driver catches any propagated runtime to keep the fan-out going.
   */
  @Override
  @Transactional
  public void runScheduledBackgroundRefresh(UUID userId) {
    if (userId == null) {
      return;
    }

    PriceFreshnessGuardrails guardrails = guardrailsProvider.getIfAvailable();
    if (guardrails != null) {
      PriceFreshnessGuardrails.Decision decision =
          guardrails.preflight(userId, PriceFreshnessGuardrails.RefreshKind.SCHEDULED);
      if (decision != PriceFreshnessGuardrails.Decision.ALLOW) {
        log.info("scheduled refresh: {} for user {} (AI cost-cap guardrail)", decision, userId);
        return;
      }
    }

    GroceryOrderDataGateway orderGateway = orderGatewayProvider.getIfAvailable();
    if (orderGateway == null) {
      log.debug("scheduled refresh: order gateway unavailable for user {} — nothing to do", userId);
      return;
    }

    Optional<GroceryProviderState> activeState =
        orderGateway.findProviderStatesByUserId(userId).stream()
            .filter(GroceryProviderState::isEnabled)
            .filter(GroceryProviderState::isScheduledRefreshEnabled)
            .findFirst();
    if (activeState.isEmpty()) {
      log.debug(
          "scheduled refresh: no enabled+scheduled provider state for user {} — skipping", userId);
      return;
    }
    GroceryProviderState providerState = activeState.get();

    GroceryProvider provider = providerFor(providerState.getProviderKey());
    if (provider == null) {
      log.debug(
          "scheduled refresh: no GroceryProvider bean for key '{}' (user {}) — skipping",
          providerState.getProviderKey(),
          userId);
      return;
    }

    int topN =
        providerState.getRefreshTopNIngredients() > 0
            ? providerState.getRefreshTopNIngredients()
            : groceryConfig.freshness().defaultRefreshTopN();
    List<String> keys = resolveTopUsedKeys(userId, topN);
    if (keys.isEmpty()) {
      log.debug("scheduled refresh: no ingredient keys for user {} — skipping", userId);
      return;
    }

    quoteAndWriteObservations(userId, providerState, provider, keys);
  }

  /**
   * Resolve the user's top-N most-used ingredient keys for the scheduled refresh.
   *
   * <p><b>LLD divergence (grocery-01g).</b> The LLD names a {@code
   * RecipeQueryService.getTopUsedIngredientKeys(userId, lookbackWeeks, n)} method; the shipped
   * recipe module exposes only {@link RecipeQueryService#findUserRecipeIngredientKeys} which
   * returns a {@code recipeId → keys} map across all active recipes (no lookback window). v1
   * derives the top-N by counting occurrences across that map and taking the most-frequent keys —
   * adequate for a curated set while the planner-driven lookback isn't shipped. Empty list when the
   * recipe surface is unavailable or the user has no active recipes.
   */
  List<String> resolveTopUsedKeys(UUID userId, int topN) {
    RecipeQueryService recipeQueryService = recipeQueryServiceProvider.getIfAvailable();
    if (recipeQueryService == null) {
      return List.of();
    }
    Map<UUID, List<String>> byRecipe = recipeQueryService.findUserRecipeIngredientKeys(userId);
    if (byRecipe == null || byRecipe.isEmpty()) {
      return List.of();
    }
    // Count occurrences across all current-version ingredient lists. LinkedHashMap preserves
    // first-seen order to make ties deterministic.
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (List<String> keys : byRecipe.values()) {
      if (keys == null) {
        continue;
      }
      for (String raw : keys) {
        String key = IngredientMappingKeys.normalise(raw);
        if (key == null || key.isEmpty()) {
          continue;
        }
        counts.merge(key, 1, Integer::sum);
      }
    }
    if (counts.isEmpty()) {
      return List.of();
    }
    // Sort by count desc, then insertion order (stable sort preserves it) — pick the first N.
    return counts.entrySet().stream()
        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
        .limit(topN)
        .map(Map.Entry::getKey)
        .toList();
  }

  /**
   * Issue one provider quote covering {@code keys} and append a {@link PriceSource#QUOTE}
   * observation per quoted line. Per LLD line 943 the on-demand and scheduled paths share the
   * "quote → write observations" mechanic; this helper is the common implementation.
   */
  void quoteAndWriteObservations(
      UUID userId,
      GroceryProviderState providerState,
      GroceryProvider provider,
      List<String> keys) {
    // Synthetic basket — one line per key, pack count 1, no preferred SKU hint.
    List<BasketDraftLine> lines = new ArrayList<>(keys.size());
    Map<UUID, String> lineKey = new LinkedHashMap<>();
    for (String key : keys) {
      UUID lineId = UUID.randomUUID();
      lineKey.put(lineId, key);
      lines.add(new BasketDraftLine(lineId, key, key, BigDecimal.ONE, "pack", null, 1, null));
    }
    BasketDraft draft =
        new BasketDraft(
            UUID.randomUUID(),
            userId,
            lines,
            new BasketDraftPreferences(false, false, false, null));

    QuoteResult result;
    try {
      result = provider.quote(draft);
    } catch (ProviderUnavailableException ex) {
      log.info(
          "scheduled refresh: provider {} unavailable for user {} ({})",
          providerState.getProviderKey(),
          userId,
          ex.getMessage());
      return;
    } catch (AiUnavailableException ex) {
      log.info(
          "scheduled refresh: AI cap reached during provider quote for user {} ({})",
          userId,
          ex.getMessage());
      return;
    }

    if (result == null || result.lineResults() == null) {
      return;
    }
    Instant observedAt = clock.instant();
    int written = 0;
    for (Map.Entry<UUID, QuoteLineResult> entry : result.lineResults().entrySet()) {
      QuoteLineResult lineResult = entry.getValue();
      if (lineResult == null || lineResult.quotedUnitPence() == null) {
        continue;
      }
      String key = lineKey.get(entry.getKey());
      if (key == null) {
        continue;
      }
      Integer packCount =
          lineResult.packCountResolved() != null ? lineResult.packCountResolved() : 1;
      Integer totalPence = lineResult.quotedUnitPence() * Math.max(packCount, 1);
      priceObservationWriter.write(
          new PriceObservationWriter.WriteCommand(
              userId,
              // single-user mode: userId doubles as the household scope until configured
              userId,
              key,
              providerState.getProviderKey(),
              lineResult.resolvedProviderProductId(),
              null,
              packCount,
              BigDecimal.ONE,
              "pack",
              totalPence,
              result.currency() != null ? result.currency() : "GBP",
              PriceSource.QUOTE,
              null,
              null,
              observedAt,
              "scheduled refresh"));
      written++;
    }
    if (written > 0) {
      log.info(
          "scheduled refresh: wrote {} QUOTE observation(s) for user {} via provider {}",
          written,
          userId,
          providerState.getProviderKey());
    }
  }

  private GroceryProvider providerFor(String providerKey) {
    if (providerKey == null) {
      return null;
    }
    return providers
        .orderedStream()
        .filter(p -> providerKey.equals(p.providerKey()))
        .findFirst()
        .orElse(null);
  }

  // ---- Tier-4 helpers ----

  private boolean nonBlank(String s) {
    return s != null && !s.isEmpty();
  }

  private static List<PriceObservation> filterByStore(List<PriceObservation> rows, String store) {
    List<PriceObservation> out = new ArrayList<>(rows.size());
    for (PriceObservation o : rows) {
      if (store.equals(o.getStore())) {
        out.add(o);
      }
    }
    return out;
  }
}
