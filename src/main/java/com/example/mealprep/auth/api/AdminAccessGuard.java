package com.example.mealprep.auth.api;

import com.example.mealprep.auth.api.dto.UserDto;
import com.example.mealprep.auth.config.AdminAccessProperties;
import com.example.mealprep.auth.domain.service.AuthQueryService;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Project-wide imperative admin-authorisation gate. Every module's admin controller injects this
 * one bean and calls {@link #requireAdmin()} at the top of each handler — a single mechanism backed
 * by a single allowlist ({@link AdminAccessProperties}, {@code mealprep.admin.user-ids}).
 *
 * <p>It lives in the auth module's public API surface alongside {@link CurrentUserResolver} (the
 * established cross-module seam for reading the current user). Cross-module callers inject this
 * {@code @Component} directly; the auth module owns the Spring Security dependency. Placing it in
 * {@code auth.api} keeps the Spring Web {@link ResponseStatusException} dependency inside an {@code
 * ..api..} package, satisfying the {@code springWebStaysInApi} architectural rule.
 *
 * <p><b>Why imperative, not {@code @PreAuthorize}.</b> The project does not enable Spring
 * method-security ({@code @EnableMethodSecurity} is absent), so the {@code @PreAuthorize("hasRole
 * ('ADMIN')")} annotations on the admin controllers are inert — and the flat v1 user model attaches
 * only {@code ROLE_USER}, so there is no {@code ROLE_ADMIN} authority to gate on. Enabling
 * method-security project-wide would 403 every admin endpoint at once (no principal has the
 * authority). Instead this guard enforces admin access against the explicit, config-driven
 * allowlist above. The {@code @PreAuthorize} annotations remain on the controllers as the published
 * contract; when project-wide method-security lands they activate and this guard can be retired.
 *
 * <p>Enforcement is fail-closed: an anonymous request is 401 (the deny-by-default {@code
 * AuthSecurityConfig} chain already enforces this before the controller is reached, and this guard
 * also rejects a {@code null} principal), and an authenticated-but-not-allowlisted user is 403.
 * With the default empty allowlist, <em>every</em> authenticated non-admin is denied — the gate is
 * real.
 */
@Component
public class AdminAccessGuard {

  private final CurrentUserResolver currentUserResolver;
  private final AdminAccessProperties adminProperties;
  private final AuthQueryService authQueryService;

  public AdminAccessGuard(
      CurrentUserResolver currentUserResolver,
      AdminAccessProperties adminProperties,
      AuthQueryService authQueryService) {
    this.currentUserResolver = currentUserResolver;
    this.adminProperties = adminProperties;
    this.authQueryService = authQueryService;
  }

  /**
   * Enforce that the current request is from an authenticated admin user. Throws {@link
   * ResponseStatusException} 401 if anonymous, 403 if authenticated but not on the admin allowlist.
   *
   * <p>The id allowlist is checked first; the username allowlist (environments whose seed user has
   * no stable id, e.g. the e2e stack) costs one user lookup and only runs when configured.
   */
  public void requireAdmin() {
    UUID userId =
        currentUserResolver
            .currentUserId()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authentication required."));
    if (adminProperties.isAdmin(userId)) {
      return;
    }
    if (!adminProperties.usernames().isEmpty()) {
      String username = authQueryService.getUser(userId).map(UserDto::username).orElse(null);
      if (adminProperties.isAdminUsername(username)) {
        return;
      }
    }
    throw new ResponseStatusException(
        HttpStatus.FORBIDDEN, "Admin privileges required for this endpoint.");
  }
}
