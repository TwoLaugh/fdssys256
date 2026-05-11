package com.example.mealprep.provisions.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.provisions.api.dto.RecordSubstitutionRequest;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.provisions.api.dto.UpsertSupplierProductRequest;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the supplier-product aggregate. All endpoints require authentication; the resource
 * itself is global reference data with no per-user ownership. The substitution endpoint resolves
 * the {@code actorUserId} via {@link CurrentUserResolver} for the audit-trail event payload.
 *
 * <p>Insert vs update on {@code POST /} is decided server-side by the natural key {@code (supplier,
 * productId)} — 201 with {@code Location} on insert, 200 on update.
 */
@RestController
@RequestMapping("/api/v1/provisions/supplier-products")
@Tag(name = "Provisions")
@Validated
public class SupplierProductsController {

  private static final int MAX_PAGE_SIZE = 100;

  private final ProvisionQueryService queryService;
  private final ProvisionUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public SupplierProductsController(
      ProvisionQueryService queryService,
      ProvisionUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated supplier-products search; both filters optional.")
  public Page<SupplierProductDto> search(
      @RequestParam(required = false) @Size(max = 128) String mappingKey,
      @RequestParam(required = false) @Size(max = 32) String supplier,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
    requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("lastChecked")));
    return queryService.searchSupplierProducts(mappingKey, supplier, pageable);
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Insert or update a supplier product keyed by (supplier, productId). Refreshes"
              + " lastChecked on every call; preserves substitutionHistory.")
  public ResponseEntity<SupplierProductDto> upsert(
      @Valid @RequestBody UpsertSupplierProductRequest request) {
    requireCurrentUserId();
    ProvisionUpdateService.UpsertResult<SupplierProductDto> result =
        updateService.upsertSupplierProduct(request);
    SupplierProductDto saved = result.value();
    if (result.created()) {
      URI location = URI.create("/api/v1/provisions/supplier-products/" + saved.id());
      return ResponseEntity.status(HttpStatus.CREATED).location(location).body(saved);
    }
    return ResponseEntity.ok(saved);
  }

  @PostMapping(
      path = "/{supplierProductId}/substitutions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Append a substitution event to a supplier product's history.")
  public SupplierProductDto recordSubstitution(
      @PathVariable UUID supplierProductId, @Valid @RequestBody RecordSubstitutionRequest request) {
    UUID actorUserId = requireCurrentUserId();
    return updateService.recordSubstitution(
        supplierProductId,
        request.record(),
        request.userAccepted(),
        actorUserId,
        request.expectedVersion());
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
