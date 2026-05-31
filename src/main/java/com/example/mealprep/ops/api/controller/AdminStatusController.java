package com.example.mealprep.ops.api.controller;

import com.example.mealprep.auth.api.AdminAccessGuard;
import com.example.mealprep.ops.api.dto.AdminStatusDto;
import com.example.mealprep.ops.domain.service.AdminStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Custom operational status endpoint — {@code GET /api/v1/admin/status} (capability C-G-032).
 * Aggregates DB connectivity, last AI / USDA call timestamps, and month-to-date AI cost into one
 * snapshot for an operator dashboard.
 *
 * <p><b>Authorisation.</b> Gated by the shared {@link AdminAccessGuard#requireAdmin()} — the single
 * project-wide admin-allowlist mechanism ({@code mealprep.admin.user-ids}). As with {@code
 * AdminAiController}, the {@code @PreAuthorize} is the published contract but inert in v1 (no
 * project-wide method-security yet); the imperative guard is the real gate. Anonymous ⇒ 401 (also
 * enforced by the deny-by-default security chain), authenticated-but-not-allowlisted ⇒ 403,
 * fail-closed on an empty allowlist. When project-wide method-security lands the guard can be
 * retired in favour of the annotation.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "AdminStatus", description = "Operational system-status snapshot for operators.")
public class AdminStatusController {

  private final AdminStatusService statusService;
  private final AdminAccessGuard adminGuard;

  public AdminStatusController(AdminStatusService statusService, AdminAccessGuard adminGuard) {
    this.statusService = statusService;
    this.adminGuard = adminGuard;
  }

  @GetMapping("/status")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Operational status: DB connectivity, last AI/USDA call, month-to-date AI cost.",
      description =
          "Admin-only. status=UP when the database is reachable, DEGRADED otherwise. Timestamps are"
              + " null when no such call has occurred; lastUsdaCallAt is a process-local signal that"
              + " resets on restart.")
  public AdminStatusDto getStatus() {
    adminGuard.requireAdmin();
    return statusService.currentStatus();
  }
}
