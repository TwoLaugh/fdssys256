package com.example.mealprep.ai.config;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Admin-authorisation configuration for the AI observability surface — bound to {@code
 * mealprep.ai.admin.*}.
 *
 * <p>The project does <em>not</em> enable Spring method-security ({@code @EnableMethodSecurity} is
 * absent) and the flat v1 user model has no {@code ROLE_ADMIN} authority (see {@code
 * AuthSecurityConfig} / {@code SessionAuthenticationFilter}, which attaches only {@code
 * ROLE_USER}). The {@code @PreAuthorize("hasRole('ADMIN')")} annotations on {@code
 * AdminAiController} are therefore inert. Rather than turning on method-security project-wide
 * (which would simultaneously 403 every other module's admin endpoint, since no principal has the
 * authority), the AI module enforces admin access <em>imperatively</em> against this explicit
 * allowlist — mirroring the established {@code PlannerAuth} / {@code
 * DiscoveryAdminController.requireAuthenticated} idiom.
 *
 * <p>{@code userIds} is the set of user ids permitted to reach the admin AI endpoints. In v1 it is
 * empty by default — meaning <strong>no authenticated user is an AI admin</strong> until an
 * operator configures {@code mealprep.ai.admin.user-ids=<uuid>,<uuid>}. This is fail-closed: an
 * unset allowlist denies everyone (403) rather than admitting everyone.
 */
@ConfigurationProperties(prefix = "mealprep.ai.admin")
public record AiAdminProperties(List<UUID> userIds) {

  public AiAdminProperties {
    userIds = userIds == null ? List.of() : List.copyOf(userIds);
  }

  /** True when {@code userId} is in the configured admin allowlist. */
  public boolean isAdmin(UUID userId) {
    return userId != null && Set.copyOf(userIds).contains(userId);
  }
}
