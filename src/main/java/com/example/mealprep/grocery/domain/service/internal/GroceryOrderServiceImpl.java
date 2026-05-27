package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.api.dto.CancelOrderRequest;
import com.example.mealprep.grocery.api.dto.CreateOrderRequest;
import com.example.mealprep.grocery.api.dto.GroceryOrderDto;
import com.example.mealprep.grocery.api.dto.GroceryProviderStateDto;
import com.example.mealprep.grocery.api.dto.GrocerySubstitutionProposalDto;
import com.example.mealprep.grocery.api.dto.PlaceOrderRequest;
import com.example.mealprep.grocery.api.dto.ProviderConnectionRequest;
import com.example.mealprep.grocery.api.dto.QuoteRequest;
import com.example.mealprep.grocery.api.dto.ResolveSubstitutionRequest;
import com.example.mealprep.grocery.domain.service.GroceryOrderService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skeleton implementation of the Tier-3 {@link GroceryOrderService}. Per lld/grocery.md lines
 * 592-614 and ticket-01a §Service interfaces.
 *
 * <p>DIVERGENCE (ticket 01a): Tier 3 is a sibling impl, NOT folded into {@link GroceryServiceImpl},
 * because {@code GroceryOrderService.getById(UUID)} / {@code getByIds(List&lt;UUID&gt;)} clash by
 * erasure with the same-named Tier-1 methods (different return type, identical signature — Java
 * forbids both in one class). The interface contract is unchanged; both impls live in the {@code
 * internal} package and Spring injects one bean per interface. See {@link GroceryServiceImpl}.
 *
 * <p><b>01a ships ZERO behaviour</b> — every method throws {@link UnsupportedOperationException},
 * tagged with its owning tier ticket (01e order lifecycle / 01f substitutions). Read methods are
 * {@code @Transactional(readOnly = true)}; writes are {@code @Transactional}.
 */
@Service
public class GroceryOrderServiceImpl implements GroceryOrderService {

  @Override
  @Transactional(readOnly = true)
  public Optional<GroceryOrderDto> getById(UUID orderId) {
    throw new UnsupportedOperationException("implemented in grocery-01e");
  }

  @Override
  @Transactional(readOnly = true)
  public List<GroceryOrderDto> getByIds(List<UUID> ids) {
    throw new UnsupportedOperationException("implemented in grocery-01e");
  }

  @Override
  @Transactional(readOnly = true)
  public Page<GroceryOrderDto> getMyOrders(UUID userId, Pageable pageable) {
    throw new UnsupportedOperationException("implemented in grocery-01e");
  }

  @Override
  @Transactional
  public GroceryOrderDto createDraft(UUID userId, CreateOrderRequest request) {
    throw new UnsupportedOperationException("implemented in grocery-01e");
  }

  @Override
  @Transactional
  public GroceryOrderDto quote(UUID userId, QuoteRequest request) {
    throw new UnsupportedOperationException("implemented in grocery-01e");
  }

  @Override
  @Transactional
  public GroceryOrderDto placeOrder(UUID userId, PlaceOrderRequest request) {
    throw new UnsupportedOperationException("implemented in grocery-01e");
  }

  @Override
  @Transactional
  public GroceryOrderDto markUserConfirmed(UUID userId, UUID orderId) {
    throw new UnsupportedOperationException("implemented in grocery-01e");
  }

  @Override
  @Transactional
  public GroceryOrderDto refreshStatus(UUID userId, UUID orderId) {
    throw new UnsupportedOperationException("implemented in grocery-01e");
  }

  @Override
  @Transactional
  public GroceryOrderDto markDelivered(UUID userId, UUID orderId) {
    throw new UnsupportedOperationException("implemented in grocery-01e");
  }

  @Override
  @Transactional
  public GroceryOrderDto cancel(UUID userId, CancelOrderRequest request) {
    throw new UnsupportedOperationException("implemented in grocery-01e");
  }

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

  @Override
  @Transactional(readOnly = true)
  public GroceryProviderStateDto getProviderState(UUID userId, String providerKey) {
    throw new UnsupportedOperationException("implemented in grocery-01e");
  }

  @Override
  @Transactional
  public GroceryProviderStateDto upsertProviderConnection(
      UUID userId, ProviderConnectionRequest request) {
    throw new UnsupportedOperationException("implemented in grocery-01e");
  }
}
