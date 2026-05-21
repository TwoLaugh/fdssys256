package com.example.mealprep.provisions.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.core.api.markers.BoundedCollection;
import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.provisions.api.dto.UpsertEquipmentRequest;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the provisions module's equipment aggregate. Authentication is enforced by the auth
 * module's deny-by-default chain; the {@link CurrentUserResolver} resolves the caller's {@code
 * userId} server-side — the controller never accepts a {@code userId} from path, query or body.
 */
@RestController
@RequestMapping("/api/v1/provisions/equipment")
@Tag(name = "Provisions")
@Validated
public class EquipmentController {

  private static final String NAME_PATTERN = "^[a-z0-9_]+$";

  private final ProvisionQueryService queryService;
  private final ProvisionUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public EquipmentController(
      ProvisionQueryService queryService,
      ProvisionUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List the calling user's equipment.")
  @BoundedCollection("bounded by user kitchen inventory; typically < 50 items")
  public List<EquipmentDto> list() {
    UUID userId = requireCurrentUserId();
    return queryService.getEquipment(userId);
  }

  @PutMapping(
      path = "/{name}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Create or update an equipment row by canonical name.")
  public ResponseEntity<EquipmentDto> upsert(
      @PathVariable
          @Size(min = 1, max = 64)
          @Pattern(regexp = NAME_PATTERN, message = "name must match " + NAME_PATTERN)
          String name,
      @Valid @RequestBody UpsertEquipmentRequest request) {
    UUID userId = requireCurrentUserId();
    ProvisionUpdateService.UpsertResult<EquipmentDto> result =
        updateService.upsertEquipment(userId, name, request);
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status).body(result.value());
  }

  @DeleteMapping(path = "/{name}")
  @Operation(summary = "Delete an equipment row.")
  public ResponseEntity<Void> delete(
      @PathVariable
          @Size(min = 1, max = 64)
          @Pattern(regexp = NAME_PATTERN, message = "name must match " + NAME_PATTERN)
          String name) {
    UUID userId = requireCurrentUserId();
    updateService.deleteEquipment(userId, name);
    return ResponseEntity.noContent().build();
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
