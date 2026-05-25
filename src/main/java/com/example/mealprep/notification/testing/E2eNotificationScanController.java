package com.example.mealprep.notification.testing;

import com.example.mealprep.notification.scanner.ExpiryWarningScanner;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E-only HTTP control plane for firing a notification scanner ON DEMAND, synchronously, instead
 * of waiting on its production {@code @Scheduled} cadence.
 *
 * <p><b>Why this exists.</b> The notification scanners are {@code @Scheduled} sweeps — the
 * expiry-warning scanner runs daily at 06:00 ({@code ExpiryWarningScanner#runScheduled}). A
 * black-box E2E (HTTP-only, decision D2) cannot time-travel the scheduler to observe a scan's
 * effect within a scenario. This controller invokes the SAME module-internal {@code scan()} method
 * the {@code @Scheduled} trigger delegates to, so a scenario can set up the real precondition (a
 * provisions inventory item with a near-term expiry, created over the real {@code POST
 * /api/v1/provisions/inventory}), fire the scan, and then assert the resulting notification appears
 * on the user's inbox. The production {@code @Scheduled} wiring is untouched — this is an
 * additional, fixture-only entry-point onto the existing scan path, not a change to it.
 *
 * <p><b>Async observation.</b> {@code scan()} is {@code @Transactional} and publishes an {@code
 * ItemNearingExpiryEvent}; the notification is created by {@code ProvisionEventListener}'s {@code
 * AFTER_COMMIT} listener — i.e. after THIS request's transaction commits, once the response has
 * returned. The scan is therefore a TRIGGER, not a synchronous producer of the notification: a
 * scenario must POLL the inbox after firing it (the same async pattern the feedback suite uses).
 *
 * <p><b>Access seam.</b> Calls the public-within-module {@link ExpiryWarningScanner} bean directly
 * — no widening of any cross-module API; the scan is system-actor behaviour (no {@code userId}
 * param, it sweeps every user with active inventory, so a scenario can only ever surface its own
 * seeded near-expiry item). This mirrors how the in-process {@code ScannerIdempotencyIT} invokes
 * {@code scan()} on the autowired bean.
 *
 * <p><b>Strictly {@code e2e}-profile-gated</b> (mirrors {@link E2eNotificationSeedController} /
 * {@code E2eAiStubController}): the bean and its {@code /test-support/notification/**} mappings do
 * not exist under {@code prod}/{@code dev}/{@code test} (unmapped 404 in prod). ArchUnit's {@code
 * springWebStaysInApi} has a {@code ..testing..} carve-out, so this {@code notification.testing}
 * controller is allowed to depend on Spring Web.
 */
@RestController
@RequestMapping("/test-support/notification")
@Profile("e2e")
@Tag(name = "E2E Test Support")
public class E2eNotificationScanController {

  private final ExpiryWarningScanner expiryWarningScanner;

  public E2eNotificationScanController(ExpiryWarningScanner expiryWarningScanner) {
    this.expiryWarningScanner = expiryWarningScanner;
  }

  /**
   * Fire the expiry-warning scan synchronously (the same {@code scan()} the daily
   * {@code @Scheduled} trigger delegates to). Returns the number of users for whom a warning fired
   * this run. The resulting notification(s) are created on the {@code AFTER_COMMIT} listener once
   * this request's transaction commits, so a caller polls the inbox afterwards.
   *
   * @return 200 with {@code {"firedForUsers": <int>}}
   */
  @PostMapping(path = "/run-expiry-scan", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Integer>> runExpiryScan() {
    int fired = expiryWarningScanner.scan();
    return ResponseEntity.ok(Map.of("firedForUsers", fired));
  }
}
