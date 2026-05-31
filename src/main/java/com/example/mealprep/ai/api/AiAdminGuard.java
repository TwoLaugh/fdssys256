package com.example.mealprep.ai.api;

import com.example.mealprep.ai.config.AiAdminProperties;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Imperative admin-authorisation gate for the AI observability endpoints (finding {@code ai-10}).
 *
 * <p><b>Why imperative, not {@code @PreAuthorize}.</b> The project does not enable Spring
 * method-security ({@code @EnableMethodSecurity} is absent), so the {@code @PreAuthorize("hasRole
 * ('ADMIN')")} annotations on {@code AdminAiController} are inert — and the flat v1 user model
 * attaches only {@code ROLE_USER} (see {@code SessionAuthenticationFilter}), so there is no {@code
 * ROLE_ADMIN} authority to gate on. Enabling method-security globally would activate every other
 * module's identical (inert) annotation at once and 403 their admin endpoints, since no principal
 * has the authority — a project-wide breakage well outside this module's scope. Instead this guard
 * enforces admin access against an explicit, config-driven allowlist ({@link AiAdminProperties}),
 * matching the established {@code PlannerAuth} / {@code
 * DiscoveryAdminController.requireAuthenticated} idiom: resolve the caller server-side and map a
 * non-admin verdict to 403.
 *
 * <p>Enforcement is fail-closed: an anonymous request is 401 (the deny-by-default {@code
 * AuthSecurityConfig} chain already enforces this before the controller is reached, and this guard
 * also rejects a {@code null} principal), and an authenticated-but-not-allowlisted user is 403.
 * With the default empty allowlist, <em>every</em> authenticated non-admin is denied — the gate is
 * real.
 */
@Component
public class AiAdminGuard {

  private final CurrentUserResolver currentUserResolver;
  private final AiAdminProperties adminProperties;

  public AiAdminGuard(CurrentUserResolver currentUserResolver, AiAdminProperties adminProperties) {
    this.currentUserResolver = currentUserResolver;
    this.adminProperties = adminProperties;
  }

  /**
   * Enforce that the current request is from an authenticated AI-admin user. Throws {@link
   * ResponseStatusException} 401 if anonymous, 403 if authenticated but not on the admin allowlist.
   */
  public void requireAdmin() {
    UUID userId =
        currentUserResolver
            .currentUserId()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authentication required."));
    if (!adminProperties.isAdmin(userId)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Admin privileges required for the AI observability surface.");
    }
  }
}
