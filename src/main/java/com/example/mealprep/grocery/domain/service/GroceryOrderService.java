package com.example.mealprep.grocery.domain.service;

import com.example.mealprep.grocery.api.dto.CancelOrderRequest;
import com.example.mealprep.grocery.api.dto.CreateOrderRequest;
import com.example.mealprep.grocery.api.dto.GroceryOrderDto;
import com.example.mealprep.grocery.api.dto.GroceryProviderStateDto;
import com.example.mealprep.grocery.api.dto.GrocerySubstitutionProposalDto;
import com.example.mealprep.grocery.api.dto.PlaceOrderRequest;
import com.example.mealprep.grocery.api.dto.ProviderConnectionRequest;
import com.example.mealprep.grocery.api.dto.QuoteRequest;
import com.example.mealprep.grocery.api.dto.ResolveSubstitutionRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Tier 3 — grocery order via provider. Public service contract (declarations only in 01a;
 * implemented in grocery-01e/01f). Per lld/grocery.md lines 592-614. Each transition method is one
 * allowed edge (or set of related edges) of the order state machine.
 */
public interface GroceryOrderService {

  Optional<GroceryOrderDto> getById(UUID orderId);

  List<GroceryOrderDto> getByIds(List<UUID> ids);

  Page<GroceryOrderDto> getMyOrders(UUID userId, Pageable pageable);

  GroceryOrderDto createDraft(UUID userId, CreateOrderRequest request);

  GroceryOrderDto quote(UUID userId, QuoteRequest request);

  GroceryOrderDto placeOrder(UUID userId, PlaceOrderRequest request);

  GroceryOrderDto markUserConfirmed(UUID userId, UUID orderId);

  GroceryOrderDto refreshStatus(UUID userId, UUID orderId);

  GroceryOrderDto markDelivered(UUID userId, UUID orderId);

  GroceryOrderDto cancel(UUID userId, CancelOrderRequest request);

  // Substitutions
  GrocerySubstitutionProposalDto resolveSubstitution(
      UUID userId, ResolveSubstitutionRequest request);

  List<GrocerySubstitutionProposalDto> getOutstandingProposals(UUID orderId);

  // Provider connection management
  GroceryProviderStateDto getProviderState(UUID userId, String providerKey);

  GroceryProviderStateDto upsertProviderConnection(UUID userId, ProviderConnectionRequest request);
}
