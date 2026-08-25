package com.example.mealprep.auth.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Project-wide admin-authorisation allowlist — bound to {@code mealprep.admin.*}.
 *
 * <p>This is the <em>single</em> source of truth for "who is an admin" across every module. It
 * backs {@link com.example.mealprep.auth.api.AdminAccessGuard}, which all admin controllers (ai,
 * ops, planner, discovery, core-audit, adaptation, …) invoke imperatively.
 *
 * <p><b>Why imperative, not {@code @PreAuthorize}.</b> The project does <em>not</em> enable Spring
 * method-security ({@code @EnableMethodSecurity} is absent) and the flat v1 user model attaches
 * only {@code ROLE_USER} (see {@code SessionAuthenticationFilter}) — so the
 * {@code @PreAuthorize("hasRole('ADMIN')")} annotations on the admin controllers are inert and
 * there is no {@code ROLE_ADMIN} authority to gate on. Enabling method-security project-wide would
 * 403 every admin endpoint at once (no principal has the authority). Instead the {@code
 * AdminAccessGuard} enforces admin access imperatively against this explicit allowlist, matching
 * the established {@code PlannerAuth} / {@code CurrentUserResolver} idiom.
 *
 * <p>{@code userIds} is the set of user ids permitted to reach any admin endpoint. In v1 it is
 * empty by default — meaning <strong>no authenticated user is an admin</strong> until an operator
 * configures {@code mealprep.admin.user-ids=<uuid>,<uuid>}. This is fail-closed: an unset allowlist
 * denies everyone (403) rather than admitting everyone.
 *
 * <p>{@code usernames} is the same allowlist keyed by username instead of id, for environments
 * whose admin user is created at runtime with an unpredictable id (the e2e/dogfood stack registers
 * its seed user over REST, so its UUID cannot be pinned in config but the username can). Matched
 * case-insensitively against the same trim+lowercase normalisation registration uses. Empty by
 * default, so production stays fail-closed on ids alone.
 *
 * <p>Supersedes the AI module's earlier {@code mealprep.ai.admin.user-ids} key (ai-10), which was a
 * single-module precursor; the AI + ops surfaces now read this shared key.
 */
@ConfigurationProperties(prefix = "mealprep.admin")
public record AdminAccessProperties(List<UUID> userIds, List<String> usernames) {

  public AdminAccessProperties {
    userIds = userIds == null ? List.of() : List.copyOf(userIds);
    usernames =
        usernames == null
            ? List.of()
            : usernames.stream().map(AdminAccessProperties::normalise).toList();
  }

  /** True when {@code userId} is in the configured admin allowlist. */
  public boolean isAdmin(UUID userId) {
    return userId != null && Set.copyOf(userIds).contains(userId);
  }

  /** True when {@code username} (normalised) is in the configured admin username allowlist. */
  public boolean isAdminUsername(String username) {
    return username != null && usernames.contains(normalise(username));
  }

  private static String normalise(String username) {
    return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
  }
}
