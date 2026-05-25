package com.example.mealprep.notification.testing;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.notification.api.dto.CreateNotificationRequest;
import com.example.mealprep.notification.api.dto.NotificationDto;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPayload;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * E2E-only HTTP control plane for seeding a single UNREAD notification for the authenticated user.
 *
 * <p><b>Why this exists.</b> The notification inbox is purely reactive — a notification is only
 * ever produced by an event-driven listener or a {@code @Scheduled} scanner (see {@code
 * NotificationServiceImpl#create} — listener-facing, deliberately NOT bound to any REST endpoint,
 * to keep the dispatcher's single-producer debouncing assumption). So a fresh user has NO
 * notification and there is no public HTTP path to create one. The
 * read/mark-read/dismiss/action/transition surface and its state-machine errors therefore cannot be
 * exercised over the black-box E2E suite without first materialising a real notification row.
 * Driving one through a scanner needs time-travel + cross-module Provisions/Planner state; through
 * an event needs the upstream module to commit + the async listener to land — both far outside an
 * inbox fixture's scope. This seeder instead persists ONE real {@code Notification} via the
 * module's public {@link NotificationUpdateService#create(CreateNotificationRequest)} command (the
 * exact same call the dispatcher makes), so the inbox lifecycle (read → dismiss, illegal re-read,
 * ownership scoping) is exercised against a genuine row.
 *
 * <p><b>Access seam.</b> Calls the public {@code NotificationUpdateService.create} command — no
 * widening of any API; this is fixture state, not a product write path. The current user is
 * resolved server-side via {@link CurrentUserResolver}; the seeder never accepts a {@code userId}
 * param, so a scenario can only seed its own user's notification.
 *
 * <p><b>Strictly {@code e2e}-profile-gated</b> (mirrors {@code E2eFeedbackSeedController} / {@code
 * E2eAiStubController}): the bean and its {@code /test-support/notification/**} mappings do not
 * exist under {@code prod}/{@code dev}/{@code test} (unmapped 404 in prod). ArchUnit's {@code
 * springWebStaysInApi} has a {@code ..testing..} carve-out, so this {@code notification.testing}
 * controller is allowed.
 */
@RestController
@RequestMapping("/test-support/notification")
@Profile("e2e")
@Tag(name = "E2E Test Support")
public class E2eNotificationSeedController {

  private final NotificationUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public E2eNotificationSeedController(
      NotificationUpdateService updateService, CurrentUserResolver currentUserResolver) {
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  /**
   * Seed one UNREAD {@code HEALTH_DIRECTIVE_RECEIVED} notification for the calling user (the
   * simplest non-null payload shape). Returns the persisted notification id so a scenario can
   * address its read/mark-read/transition assertions.
   *
   * @return 201 with the created {@link NotificationDto}
   */
  @PostMapping(path = "/seed", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<NotificationDto> seedNotification() {
    UUID userId = requireCurrentUserId();
    NotificationPayload payload =
        new NotificationPayload.HealthDirectivePayload(
            NotificationKind.HEALTH_DIRECTIVE_RECEIVED,
            UUID.randomUUID(),
            "e2e seeded directive pending review");
    CreateNotificationRequest request =
        new CreateNotificationRequest(
            userId,
            null,
            NotificationKind.HEALTH_DIRECTIVE_RECEIVED,
            NotificationSeverity.ATTENTION,
            "Health review available",
            "A proposed directive is pending your review.",
            payload,
            null,
            UUID.randomUUID(),
            UUID.randomUUID());
    NotificationDto created = updateService.create(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
