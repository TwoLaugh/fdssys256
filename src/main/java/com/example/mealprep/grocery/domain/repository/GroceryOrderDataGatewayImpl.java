package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.domain.entity.GroceryProviderState;
import com.example.mealprep.grocery.domain.entity.GrocerySubstitutionProposal;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import com.example.mealprep.grocery.domain.service.internal.GroceryOrderDataGateway;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Package-private adapter implementing the public {@link GroceryOrderDataGateway} port
 * (grocery-01e). Co-located with the package-private grocery repositories so it can hold them (they
 * are not visible outside this package — {@code GroceryBoundaryTest.reposArePackagePrivate}). The
 * Tier-3 service / assembler in {@code domain.service.internal} inject the port, never the
 * repositories. Mirrors {@code ShoppingListDataGatewayImpl}.
 */
@Component
class GroceryOrderDataGatewayImpl implements GroceryOrderDataGateway {

  /** Orders excluded from the "my orders" listing are the terminal-archive states. */
  private static final List<GroceryOrderStatus> EXCLUDED_FROM_LISTING =
      List.of(GroceryOrderStatus.ARCHIVED);

  private final GroceryOrderRepository orderRepository;
  private final GroceryOrderLineRepository orderLineRepository;
  private final GroceryProviderStateRepository providerStateRepository;
  private final ShoppingListRepository shoppingListRepository;
  private final GrocerySubstitutionProposalRepository proposalRepository;

  GroceryOrderDataGatewayImpl(
      GroceryOrderRepository orderRepository,
      GroceryOrderLineRepository orderLineRepository,
      GroceryProviderStateRepository providerStateRepository,
      ShoppingListRepository shoppingListRepository,
      GrocerySubstitutionProposalRepository proposalRepository) {
    this.orderRepository = orderRepository;
    this.orderLineRepository = orderLineRepository;
    this.providerStateRepository = providerStateRepository;
    this.shoppingListRepository = shoppingListRepository;
    this.proposalRepository = proposalRepository;
  }

  @Override
  public Optional<GroceryOrder> findOrderWithLinesById(UUID orderId) {
    return orderRepository.findWithLinesById(orderId);
  }

  @Override
  public Optional<GroceryOrder> findOrderById(UUID orderId) {
    return orderRepository.findById(orderId);
  }

  @Override
  public List<GroceryOrder> findOrdersByIds(List<UUID> ids) {
    return orderRepository.findAllById(ids);
  }

  @Override
  public Page<GroceryOrder> findMyOrders(UUID userId, Pageable pageable) {
    return orderRepository.findAllByUserIdAndStatusNotInOrderByCreatedAtDesc(
        userId, EXCLUDED_FROM_LISTING, pageable);
  }

  @Override
  public List<GroceryOrder> findActiveOrdersByShoppingListId(UUID shoppingListId) {
    return orderRepository.findActiveByShoppingListId(shoppingListId);
  }

  @Override
  public GroceryOrder saveOrder(GroceryOrder order) {
    return orderRepository.save(order);
  }

  @Override
  public GroceryOrder saveAndFlushOrder(GroceryOrder order) {
    return orderRepository.saveAndFlush(order);
  }

  @Override
  public Optional<ShoppingList> findShoppingListWithLinesById(UUID shoppingListId) {
    return shoppingListRepository.findWithLinesById(shoppingListId);
  }

  @Override
  public GrocerySubstitutionProposal saveProposal(GrocerySubstitutionProposal proposal) {
    return proposalRepository.save(proposal);
  }

  @Override
  public GrocerySubstitutionProposal saveAndFlushProposal(GrocerySubstitutionProposal proposal) {
    return proposalRepository.saveAndFlush(proposal);
  }

  @Override
  public Optional<GrocerySubstitutionProposal> findProposalById(UUID proposalId) {
    return proposalRepository.findById(proposalId);
  }

  @Override
  public List<GrocerySubstitutionProposal> findProposalsByOrderId(UUID orderId) {
    return proposalRepository.findAllByGroceryOrderId(orderId);
  }

  @Override
  public List<GrocerySubstitutionProposal> findProposalsByOrderIdAndStatus(
      UUID orderId, SubstitutionProposalStatus status) {
    return proposalRepository.findAllByGroceryOrderIdAndProposalStatus(orderId, status);
  }

  @Override
  public long countProposalsByOrderIdAndStatusIn(
      UUID orderId, List<SubstitutionProposalStatus> statuses) {
    return proposalRepository.countByGroceryOrderIdAndProposalStatusIn(orderId, statuses);
  }

  @Override
  public Optional<GroceryProviderState> findProviderState(UUID userId, String providerKey) {
    return providerStateRepository.findByUserIdAndProviderKey(userId, providerKey);
  }

  @Override
  public GroceryProviderState saveProviderState(GroceryProviderState state) {
    return providerStateRepository.save(state);
  }

  @Override
  public Optional<String> findLastPaidProviderProductId(UUID userId, String ingredientMappingKey) {
    List<String> hits =
        orderLineRepository.findLastPaidProviderProductIds(
            userId, ingredientMappingKey, PageRequest.of(0, 1));
    return hits.isEmpty() ? Optional.empty() : Optional.ofNullable(hits.get(0));
  }

  @Override
  public void updateOrderStatusAndReason(
      UUID orderId, GroceryOrderStatus status, String reason, Instant now) {
    orderRepository.updateStatusAndReason(orderId, status, reason, now);
  }

  @Override
  public void bumpProviderFailure(UUID userId, String providerKey, String reason, Instant now) {
    orderRepository.bumpProviderFailure(userId, providerKey, reason, now);
  }
}
