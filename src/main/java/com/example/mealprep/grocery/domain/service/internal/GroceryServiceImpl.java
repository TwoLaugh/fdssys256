package com.example.mealprep.grocery.domain.service.internal;

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
import com.example.mealprep.grocery.config.GroceryConfig;
import com.example.mealprep.grocery.domain.entity.PriceObservation;
import com.example.mealprep.grocery.domain.entity.PriceSource;
import com.example.mealprep.grocery.domain.service.ManualFulfilmentService;
import com.example.mealprep.grocery.domain.service.PriceHistoryService;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource.ReferencePrice;
import com.example.mealprep.grocery.domain.service.ShoppingListService;
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

  // Tier-4 collaborators (01c). Tier 1/2 fields land with 01b/01d.
  private final PriceDataGateway priceDataGateway;
  private final PriceAggregator priceAggregator;
  private final PriceObservationWriter priceObservationWriter;
  private final ReferencePriceSource referencePriceSource;
  private final PriceObservationMapper priceObservationMapper;
  private final GroceryConfig groceryConfig;
  private final Clock clock;

  public GroceryServiceImpl(
      PriceDataGateway priceDataGateway,
      PriceAggregator priceAggregator,
      PriceObservationWriter priceObservationWriter,
      ReferencePriceSource referencePriceSource,
      PriceObservationMapper priceObservationMapper,
      GroceryConfig groceryConfig,
      Clock clock) {
    this.priceDataGateway = priceDataGateway;
    this.priceAggregator = priceAggregator;
    this.priceObservationWriter = priceObservationWriter;
    this.referencePriceSource = referencePriceSource;
    this.priceObservationMapper = priceObservationMapper;
    this.groceryConfig = groceryConfig;
    this.clock = clock;
  }

  // ---------------------------------------------------------------------------------------------
  // Tier 1 — ShoppingListService (implemented in grocery-01b)
  // ---------------------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public Optional<ShoppingListDto> getCurrentByPlanId(UUID planId) {
    throw new UnsupportedOperationException("implemented in grocery-01b");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ShoppingListDto> getById(UUID shoppingListId) {
    throw new UnsupportedOperationException("implemented in grocery-01b");
  }

  @Override
  @Transactional(readOnly = true)
  public List<ShoppingListDto> getByIds(List<UUID> ids) {
    throw new UnsupportedOperationException("implemented in grocery-01b");
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ShoppingListDto> getHistory(UUID userId, Pageable pageable) {
    throw new UnsupportedOperationException("implemented in grocery-01b");
  }

  @Override
  @Transactional
  public ShoppingListDto recalculate(UUID userId, RecalculateShoppingListRequest request) {
    throw new UnsupportedOperationException("implemented in grocery-01b");
  }

  @Override
  @Transactional(readOnly = true)
  public ShoppingListExportDto export(UUID shoppingListId, ExportFormat format) {
    throw new UnsupportedOperationException("implemented in grocery-01b");
  }

  // ---------------------------------------------------------------------------------------------
  // Tier 2 — ManualFulfilmentService (implemented in grocery-01d)
  // ---------------------------------------------------------------------------------------------

  @Override
  @Transactional
  public MarkBoughtResultDto markBought(UUID userId, MarkBoughtRequest request) {
    throw new UnsupportedOperationException("implemented in grocery-01d");
  }

  @Override
  @Transactional
  public List<MarkBoughtResultDto> bulkMarkBought(UUID userId, BulkMarkBoughtRequest request) {
    throw new UnsupportedOperationException("implemented in grocery-01d");
  }

  @Override
  @Transactional
  public void undoMarkBought(UUID shoppingListLineId, UUID actorUserId) {
    throw new UnsupportedOperationException("implemented in grocery-01d");
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

  @Override
  @Transactional
  public void runScheduledBackgroundRefresh(UUID userId) {
    throw new UnsupportedOperationException("implemented in grocery-01g");
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
