package com.example.mealprep.auth.domain.service.internal;

import com.example.mealprep.auth.domain.service.AuthenticatedPrincipal;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Spring Security-backed implementation of {@link CurrentUserResolver}. Reads the principal that
 * {@code SessionAuthenticationFilter} placed on the {@code SecurityContext}.
 *
 * <p>Anonymous principals (no cookie or invalid cookie) yield {@link Optional#empty()} from both
 * methods; the controller layer translates that to 401 via Spring Security's {@code
 * AuthenticationEntryPoint}.
 */
@Component
public class CurrentUserResolverImpl implements CurrentUserResolver {

  @Override
  public Optional<UUID> currentUserId() {
    return principal().map(AuthenticatedPrincipal::userId);
  }

  @Override
  public Optional<UUID> currentSessionId() {
    return principal().map(AuthenticatedPrincipal::sessionId);
  }

  private Optional<AuthenticatedPrincipal> principal() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      return Optional.empty();
    }
    Object p = auth.getPrincipal();
    if (p instanceof AuthenticatedPrincipal ap) {
      return Optional.of(ap);
    }
    return Optional.empty();
  }
}
