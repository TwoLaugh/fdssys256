package com.example.mealprep.grocery.domain.service.internal;

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
import com.example.mealprep.grocery.domain.service.ManualFulfilmentService;
import com.example.mealprep.grocery.domain.service.PriceHistoryService;
import com.example.mealprep.grocery.domain.service.ShoppingListService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    throw new UnsupportedOperationException("implemented in grocery-01c");
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, PriceAggregateDto> getAggregatesByKeys(
      UUID householdId, Collection<String> keys) {
    throw new UnsupportedOperationException("implemented in grocery-01c");
  }

  @Override
  @Transactional(readOnly = true)
  public List<PriceAggregateDto> getCrossStoreAggregatesByKey(UUID householdId, String key) {
    throw new UnsupportedOperationException("implemented in grocery-01c");
  }

  @Override
  @Transactional(readOnly = true)
  public Page<PriceObservationDto> getObservations(UUID userId, Pageable pageable) {
    throw new UnsupportedOperationException("implemented in grocery-01c");
  }

  @Override
  @Transactional(readOnly = true)
  public Page<PriceObservationDto> getObservationsByMappingKey(
      UUID householdId, String key, Pageable pageable) {
    throw new UnsupportedOperationException("implemented in grocery-01c");
  }

  @Override
  @Transactional
  public PriceObservationDto recordManualPrice(UUID userId, RecordManualPriceRequest request) {
    throw new UnsupportedOperationException("implemented in grocery-01d");
  }

  @Override
  @Transactional
  public RefreshPricesResultDto refreshOnDemand(UUID userId, RefreshPricesRequest request) {
    throw new UnsupportedOperationException("implemented in grocery-01c");
  }

  @Override
  @Transactional
  public void runScheduledBackgroundRefresh(UUID userId) {
    throw new UnsupportedOperationException("implemented in grocery-01g");
  }
}
