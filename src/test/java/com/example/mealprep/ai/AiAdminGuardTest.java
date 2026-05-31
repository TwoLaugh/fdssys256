package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.api.AiAdminGuard;
import com.example.mealprep.ai.config.AiAdminProperties;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the imperative admin gate (finding {@code ai-10}). Proves the three verdicts:
 * anonymous → 401, authenticated-but-not-allowlisted → 403, allowlisted → permitted.
 */
class AiAdminGuardTest {

  private final CurrentUserResolver resolver = mock(CurrentUserResolver.class);

  private AiAdminGuard guard(UUID... admins) {
    return new AiAdminGuard(resolver, new AiAdminProperties(List.of(admins)));
  }

  @Test
  void anonymous_isRejectedWith401() {
    when(resolver.currentUserId()).thenReturn(Optional.empty());
    assertThatThrownBy(() -> guard().requireAdmin())
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                org.assertj.core.api.Assertions.assertThat(
                        ((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void authenticatedNonAdmin_isRejectedWith403() {
    UUID admin = UUID.randomUUID();
    UUID someoneElse = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(someoneElse));
    assertThatThrownBy(() -> guard(admin).requireAdmin())
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                org.assertj.core.api.Assertions.assertThat(
                        ((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void emptyAllowlist_deniesEveryAuthenticatedUser_failClosed() {
    when(resolver.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));
    assertThatThrownBy(() -> guard().requireAdmin())
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                org.assertj.core.api.Assertions.assertThat(
                        ((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void allowlistedAdmin_isPermitted() {
    UUID admin = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(admin));
    assertThatCode(() -> guard(admin).requireAdmin()).doesNotThrowAnyException();
  }

  @Test
  void adminProperties_nullAndEmptyHandling() {
    org.assertj.core.api.Assertions.assertThat(new AiAdminProperties(null).userIds()).isEmpty();
    UUID a = UUID.randomUUID();
    AiAdminProperties props = new AiAdminProperties(List.of(a));
    org.assertj.core.api.Assertions.assertThat(props.isAdmin(a)).isTrue();
    org.assertj.core.api.Assertions.assertThat(props.isAdmin(UUID.randomUUID())).isFalse();
    org.assertj.core.api.Assertions.assertThat(props.isAdmin(null)).isFalse();
  }
}
