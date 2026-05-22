package com.example.mealprep.notification.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.notification.api.dto.DeliveryLogEntryDto;
import com.example.mealprep.notification.api.dto.NotificationDto;
import com.example.mealprep.notification.api.dto.NotificationListFilter;
import com.example.mealprep.notification.api.dto.NotificationSummaryDto;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationStatus;
import com.example.mealprep.notification.domain.service.NotificationQueryService;
import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import com.example.mealprep.notification.exception.NotificationNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the notification inbox. {@code userId} is resolved server-side from the auth
 * context via {@link CurrentUserResolver}; the controller never accepts a {@code userId} from a
 * path/query param, so a user cannot read or mutate another user's notifications. State transitions
 * are POSTs (verbs over a resource) per the style guide.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
public class NotificationsController {

  private final NotificationQueryService queryService;
  private final NotificationUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public NotificationsController(
      NotificationQueryService queryService,
      NotificationUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "List the calling user's notifications, newest-first, with optional filters.")
  public Page<NotificationDto> list(
      @RequestParam(required = false) NotificationStatus status,
      @RequestParam(required = false) NotificationKind kind,
      @RequestParam(required = false) Instant since,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    Set<NotificationStatus> statuses = status == null ? Set.of() : EnumSet.of(status);
    Set<NotificationKind> kinds = kind == null ? Set.of() : EnumSet.of(kind);
    Pageable pageable = PageRequest.of(page, size);
    return queryService.list(userId, new NotificationListFilter(statuses, kinds, since), pageable);
  }

  @GetMapping(path = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Unread / attention / urgent badge counts for the calling user.")
  public NotificationSummaryDto summary() {
    return queryService.getSummary(requireCurrentUserId());
  }

  @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Read a single notification owned by the calling user.")
  public NotificationDto getById(@PathVariable UUID id) {
    UUID userId = requireCurrentUserId();
    return queryService
        .getById(userId, id)
        .orElseThrow(() -> new NotificationNotFoundException(id));
  }

  @PostMapping(path = "/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Mark a notification read.")
  public NotificationDto markRead(@PathVariable UUID id) {
    return updateService.markRead(requireCurrentUserId(), id);
  }

  @PostMapping(path = "/{id}/dismiss", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Dismiss a notification.")
  public NotificationDto markDismissed(@PathVariable UUID id) {
    return updateService.markDismissed(requireCurrentUserId(), id);
  }

  @PostMapping(path = "/{id}/action", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Mark a notification actioned.")
  public NotificationDto markActioned(@PathVariable UUID id) {
    return updateService.markActioned(requireCurrentUserId(), id);
  }

  @PostMapping(
      path = "/bulk/read",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Mark all unread notifications read; an empty kinds list means all kinds.")
  public Map<String, Integer> markAllRead(@Valid @RequestBody BulkReadRequest request) {
    Set<NotificationKind> kinds =
        request == null || request.kinds() == null ? Set.of() : request.kinds();
    int updated = updateService.markAllRead(requireCurrentUserId(), kinds);
    return Map.of("updated", updated);
  }

  @GetMapping(path = "/{id}/delivery-log", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated delivery-log entries for a notification; newest-first.")
  public Page<DeliveryLogEntryDto> deliveryLog(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return queryService.getDeliveryLog(userId, id, pageable);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }

  /** Body for {@code POST /bulk/read}. */
  public record BulkReadRequest(Set<NotificationKind> kinds) {}
}
