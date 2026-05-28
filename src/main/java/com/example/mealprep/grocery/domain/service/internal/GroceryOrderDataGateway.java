package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.domain.entity.GroceryProviderState;
import com.example.mealprep.grocery.domain.entity.GrocerySubstitutionProposal;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Internal data-access seam for the Tier-3 order lifecycle (grocery-01e). Exists because the
 * grocery repositories are package-private in {@code domain.repository} (enforced by {@code
 * GroceryBoundaryTest.reposArePackagePrivate}) and therefore not visible to {@code
 * domain.service.internal}. The package-private {@code GroceryOrderDataGatewayImpl} (co-located
 * WITH the repositories) implements this port and holds them; the Tier-3 service / assembler depend
 * only on this port. Mirrors {@code ShoppingListDataGateway} / {@code PriceDataGateway}.
 */
public interface GroceryOrderDataGateway {

  // ---- order reads / writes ----

  Optional<GroceryOrder> findOrderWithLinesById(UUID orderId);

  Optional<GroceryOrder> findOrderById(UUID orderId);

  List<GroceryOrder> findOrdersByIds(List<UUID> ids);

  Page<GroceryOrder> findMyOrders(UUID userId, Pageable pageable);

  List<GroceryOrder> findActiveOrdersByShoppingListId(UUID shoppingListId);

  /** All orders in {@code status} — used by 01g's hourly status / retry sweep. */
  List<GroceryOrder> findOrdersByStatus(GroceryOrderStatus status);

  /**
   * RECONCILED orders whose {@code reconciledAt} is at or before {@code threshold} — the daily
   * archival sweep target set (01g, GROC-35). The {@code reconciledAt} comparison is inclusive on
   * the 12-month boundary so "exactly 12 months ago" archives.
   */
  List<GroceryOrder> findReconciledOlderThan(Instant threshold);

  GroceryOrder saveOrder(GroceryOrder order);

  GroceryOrder saveAndFlushOrder(GroceryOrder order);

  // ---- shopping list (the draft snapshot source) ----

  Optional<ShoppingList> findShoppingListWithLinesById(UUID shoppingListId);

  // ---- substitution proposals (grocery-01f) — queried separately from the order aggregate ----

  /** Persist (insert or update) a substitution proposal. */
  GrocerySubstitutionProposal saveProposal(GrocerySubstitutionProposal proposal);

  /**
   * Persist a proposal and flush so its {@code @Version} reflects the DB state (used by the resolve
   * path so a concurrent stale resolve surfaces an {@code OptimisticLockException} → 409).
   */
  GrocerySubstitutionProposal saveAndFlushProposal(GrocerySubstitutionProposal proposal);

  /** Load a single proposal by id (404 mapping is the caller's). */
  Optional<GrocerySubstitutionProposal> findProposalById(UUID proposalId);

  /** All proposals for an order (any status). */
  List<GrocerySubstitutionProposal> findProposalsByOrderId(UUID orderId);

  /**
   * Proposals for an order in a given status (e.g. the outstanding {@code PENDING_USER_REVIEW}).
   */
  List<GrocerySubstitutionProposal> findProposalsByOrderIdAndStatus(
      UUID orderId, SubstitutionProposalStatus status);

  /** Count proposals for an order whose status is in {@code statuses} — the reconcile gate. */
  long countProposalsByOrderIdAndStatusIn(UUID orderId, List<SubstitutionProposalStatus> statuses);

  // ---- provider-state reads / writes ----

  Optional<GroceryProviderState> findProviderState(UUID userId, String providerKey);

  GroceryProviderState saveProviderState(GroceryProviderState state);

  /**
   * All {@link GroceryProviderState} rows with {@code scheduled_refresh_enabled = true} — the
   * candidate set for 01g's weekly refresh fan-out.
   */
  List<GroceryProviderState> findProviderStatesWithScheduledRefreshEnabled();

  /**
   * All {@link GroceryProviderState} rows for {@code userId}. Used by the {@code
   * CostBudgetExceededEvent} listener to pause scheduled refresh across every provider the user has
   * connected. Empty list when the user has no provider state.
   */
  List<GroceryProviderState> findProviderStatesByUserId(UUID userId);

  /**
   * The most-recently-paid provider product id for {@code (userId, ingredientMappingKey)} across
   * prior orders — the preferred-SKU hint for {@code BasketDraftAssembler}. Empty when none.
   */
  Optional<String> findLastPaidProviderProductId(UUID userId, String ingredientMappingKey);

  // ---- immediate failure-state writes (bypass the persistence context; see OrderFailureRecorder)
  // ----

  /** Immediate bulk status+reason update for the failure-forward path (REQUIRES_NEW recorder). */
  void updateOrderStatusAndReason(
      UUID orderId, GroceryOrderStatus status, String reason, Instant now);

  /** Immediate provider-state failure-counter bump (REQUIRES_NEW recorder). */
  void bumpProviderFailure(UUID userId, String providerKey, String reason, Instant now);
}
