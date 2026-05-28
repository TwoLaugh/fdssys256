package com.example.mealprep.grocery.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.core.api.markers.BoundedCollection;
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
import com.example.mealprep.grocery.exception.ProviderNotConfiguredException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tier 3 — grocery-order REST surface (grocery-01e). Per lld/grocery.md lines 707-725. {@link
 * CurrentUserResolver} resolves {@code userId} server-side.
 *
 * <p>Endpoints under {@code /api/v1/grocery/orders}: list (200), get-by-id (200/404), create
 * (201/400/404/409), quote (200/404/409/503), place (200/404/409/422/503), mark-user-confirmed
 * (200/404/409), refresh-status (200/404/503), mark-delivered (200/404/409), cancel (200/404/409),
 * substitutions list (200/404), substitution resolve (200/404/409 — body via 01f), and provider
 * connection get/upsert (200/404/400). The {@code ProviderPartialFailureException} fail-forward
 * path returns 200 (caught service-side, not an error).
 */
@RestController
@RequestMapping("/api/v1/grocery/orders")
@Tag(name = "Grocery — Order")
public class GroceryOrderController {

  private final GroceryOrderService groceryOrderService;
  private final CurrentUserResolver currentUserResolver;

  public GroceryOrderController(
      GroceryOrderService groceryOrderService, CurrentUserResolver currentUserResolver) {
    this.groceryOrderService = groceryOrderService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated grocery orders for the caller, newest-first.")
  public Page<GroceryOrderDto> myOrders(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return groceryOrderService.getMyOrders(userId, pageable);
  }

  @GetMapping(path = "/{orderId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Fetch a grocery order by id (with its lines).")
  public GroceryOrderDto getById(@PathVariable UUID orderId) {
    requireCurrentUserId();
    return groceryOrderService
        .getById(orderId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Grocery order '" + orderId + "' not found."));
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Create a draft order from a shopping list (clones the list lines).")
  public ResponseEntity<GroceryOrderDto> create(@Valid @RequestBody CreateOrderRequest request) {
    UUID userId = requireCurrentUserId();
    GroceryOrderDto dto = groceryOrderService.createDraft(userId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(dto);
  }

  @PostMapping(path = "/{orderId}/quote", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Quote a draft order against the provider (DRAFT → QUOTED).")
  public GroceryOrderDto quote(@PathVariable UUID orderId) {
    UUID userId = requireCurrentUserId();
    return groceryOrderService.quote(userId, new QuoteRequest(orderId));
  }

  @PostMapping(path = "/{orderId}/place", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Place a quoted order (drives to checkout; auto-advances to AWAITING_USER_CONFIRMATION;"
              + " never auto-confirms). A partial place returns 200 with PLACED_PARTIAL.")
  public GroceryOrderDto place(@PathVariable UUID orderId) {
    UUID userId = requireCurrentUserId();
    return groceryOrderService.placeOrder(userId, new PlaceOrderRequest(orderId));
  }

  @PostMapping(path = "/{orderId}/mark-user-confirmed", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Mark an order user-confirmed (AWAITING_USER_CONFIRMATION → CONFIRMED).")
  public GroceryOrderDto markUserConfirmed(@PathVariable UUID orderId) {
    UUID userId = requireCurrentUserId();
    return groceryOrderService.markUserConfirmed(userId, orderId);
  }

  @PostMapping(path = "/{orderId}/refresh-status", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Pull the provider's status for an order and apply lifecycle implications.")
  public GroceryOrderDto refreshStatus(@PathVariable UUID orderId) {
    UUID userId = requireCurrentUserId();
    return groceryOrderService.refreshStatus(userId, orderId);
  }

  @PostMapping(path = "/{orderId}/mark-delivered", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Mark an order delivered (CONFIRMED → DELIVERED).")
  public GroceryOrderDto markDelivered(@PathVariable UUID orderId) {
    UUID userId = requireCurrentUserId();
    return groceryOrderService.markDelivered(userId, orderId);
  }

  @PostMapping(
      path = "/{orderId}/cancel",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Cancel an order (legal until delivered; 409 after reconciled/archived).")
  public GroceryOrderDto cancel(
      @PathVariable UUID orderId, @Valid @RequestBody CancelOrderRequest request) {
    UUID userId = requireCurrentUserId();
    if (!orderId.equals(request.groceryOrderId())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Path orderId and body groceryOrderId must match.");
    }
    return groceryOrderService.cancel(userId, request);
  }

  @GetMapping(path = "/{orderId}/substitutions", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Outstanding substitution proposals for an order (resolution lands in 01f).")
  @BoundedCollection("Bounded by the number of lines an order has substitutions for (small).")
  public List<GrocerySubstitutionProposalDto> substitutions(@PathVariable UUID orderId) {
    requireCurrentUserId();
    return groceryOrderService.getOutstandingProposals(orderId);
  }

  @PostMapping(
      path = "/{orderId}/substitutions/{proposalId}/resolve",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Resolve a substitution proposal (ACCEPTED | REJECTED). Behaviour in 01f.")
  public GrocerySubstitutionProposalDto resolveSubstitution(
      @PathVariable UUID orderId,
      @PathVariable UUID proposalId,
      @Valid @RequestBody ResolveSubstitutionRequest request) {
    UUID userId = requireCurrentUserId();
    if (!proposalId.equals(request.proposalId())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Path proposalId and body proposalId must match.");
    }
    return groceryOrderService.resolveSubstitution(userId, request);
  }

  @GetMapping(path = "/providers/{providerKey}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Read the caller's provider connection state.")
  public GroceryProviderStateDto getProviderState(@PathVariable String providerKey) {
    UUID userId = requireCurrentUserId();
    try {
      return groceryOrderService.getProviderState(userId, providerKey);
    } catch (ProviderNotConfiguredException ex) {
      // Per LLD lines 722: a GET for an absent provider state is a 404 (the operation endpoints
      // map "not configured" to 422; the read maps absence to not-found).
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "No provider state for '" + providerKey + "'.");
    }
  }

  @PutMapping(
      path = "/providers/{providerKey}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Create or update the caller's provider connection (enable/disable/refresh).")
  public GroceryProviderStateDto upsertProviderConnection(
      @PathVariable String providerKey, @Valid @RequestBody ProviderConnectionRequest request) {
    UUID userId = requireCurrentUserId();
    if (!providerKey.equals(request.providerKey())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Path providerKey and body providerKey must match.");
    }
    return groceryOrderService.upsertProviderConnection(userId, request);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
