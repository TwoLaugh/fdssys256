package com.example.mealprep.notification.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.notification.api.dto.NotificationPreferenceDto;
import com.example.mealprep.notification.api.dto.UpdateNotificationPreferenceRequest;
import com.example.mealprep.notification.domain.service.NotificationQueryService;
import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for notification preferences. {@code GET} auto-seeds a defaults row on first access
 * (idempotent); {@code PUT} replaces the document with optimistic-lock and {@code @ValidQuietHours}
 * checks.
 */
@RestController
@RequestMapping("/api/v1/notifications/preferences")
@Tag(name = "Notifications")
public class NotificationPreferencesController {

  private final NotificationQueryService queryService;
  private final NotificationUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public NotificationPreferencesController(
      NotificationQueryService queryService,
      NotificationUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Read the calling user's notification preferences; auto-seeds defaults.")
  public NotificationPreferenceDto get() {
    UUID userId = requireCurrentUserId();
    // Idempotent seed-on-read so a fresh user always has a preference document to render.
    updateService.ensurePreferencesForUser(userId);
    return queryService.getPreferences(userId);
  }

  @PutMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Replace the calling user's notification preferences.")
  public NotificationPreferenceDto update(
      @Valid @RequestBody UpdateNotificationPreferenceRequest request) {
    return updateService.updatePreferences(requireCurrentUserId(), request);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
