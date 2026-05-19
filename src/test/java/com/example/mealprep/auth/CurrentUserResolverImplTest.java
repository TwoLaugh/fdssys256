package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.auth.domain.service.AuthenticatedPrincipal;
import com.example.mealprep.auth.domain.service.internal.CurrentUserResolverImpl;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link CurrentUserResolverImpl}. Drives every branch of the private {@code
 * principal()} helper via the real {@link SecurityContextHolder}: no authentication, an
 * unauthenticated token, a wrong-type principal, and a valid {@link AuthenticatedPrincipal}. This
 * kills the previously-uncovered null/!isAuthenticated/instanceof branch mutants and pins that
 * {@code currentUserId}/{@code currentSessionId} map the correct field.
 */
class CurrentUserResolverImplTest {

  private final CurrentUserResolverImpl resolver = new CurrentUserResolverImpl();

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void noAuthentication_yieldsEmptyForBothAccessors() {
    SecurityContextHolder.clearContext();

    assertThat(resolver.currentUserId()).isEmpty();
    assertThat(resolver.currentSessionId()).isEmpty();
  }

  @Test
  void unauthenticatedToken_yieldsEmpty() {
    var token = UsernamePasswordAuthenticationToken.unauthenticated("user", "pw");
    SecurityContextHolder.getContext().setAuthentication(token);
    // Sanity: this token reports isAuthenticated()==false.
    assertThat(token.isAuthenticated()).isFalse();

    assertThat(resolver.currentUserId()).isEmpty();
    assertThat(resolver.currentSessionId()).isEmpty();
  }

  @Test
  void authenticatedButWrongPrincipalType_yieldsEmpty() {
    var token =
        new UsernamePasswordAuthenticationToken(
            "plain-string-principal", null, AuthorityUtils.NO_AUTHORITIES);
    SecurityContextHolder.getContext().setAuthentication(token);
    assertThat(token.isAuthenticated()).isTrue();

    assertThat(resolver.currentUserId()).isEmpty();
    assertThat(resolver.currentSessionId()).isEmpty();
  }

  @Test
  void anonymousAuthenticationToken_withNonPrincipal_yieldsEmpty() {
    var anon =
        new AnonymousAuthenticationToken(
            "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    SecurityContextHolder.getContext().setAuthentication(anon);

    assertThat(resolver.currentUserId()).isEmpty();
    assertThat(resolver.currentSessionId()).isEmpty();
  }

  @Test
  void validAuthenticatedPrincipal_returnsUserIdAndSessionId() {
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    var principal = new AuthenticatedPrincipal(userId, sessionId);
    var token =
        new UsernamePasswordAuthenticationToken(principal, null, AuthorityUtils.NO_AUTHORITIES);
    SecurityContextHolder.getContext().setAuthentication(token);

    assertThat(resolver.currentUserId()).contains(userId);
    assertThat(resolver.currentSessionId()).contains(sessionId);
  }
}
