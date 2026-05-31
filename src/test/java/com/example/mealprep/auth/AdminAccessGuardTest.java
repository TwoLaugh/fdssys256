package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.api.AdminAccessGuard;
import com.example.mealprep.auth.config.AdminAccessProperties;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the shared project-wide admin gate ({@link AdminAccessGuard}). Proves the three
 * verdicts: anonymous → 401, authenticated-but-not-allowlisted → 403, allowlisted → permitted.
 *
 * <p>Supersedes the AI module's {@code AiAdminGuardTest}: the guard + allowlist were consolidated
 * into the auth module ({@code mealprep.admin.user-ids}) so every module shares one mechanism.
 */
class AdminAccessGuardTest {

  private final CurrentUserResolver resolver = mock(CurrentUserResolver.class);

  private AdminAccessGuard guard(UUID... admins) {
    return new AdminAccessGuard(resolver, new AdminAccessProperties(List.of(admins)));
  }

  @Test
  void anonymous_isRejectedWith401() {
    when(resolver.currentUserId()).thenReturn(Optional.empty());
    assertThatThrownBy(() -> guard().requireAdmin())
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void authenticatedNonAdmin_isRejectedWith403() {
    UUID admin = UUID.randomUUID();
    UUID someoneElse = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(someoneElse));
    assertThatThrownBy(() -> guard(admin).requireAdmin())
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void emptyAllowlist_deniesEveryAuthenticatedUser_failClosed() {
    when(resolver.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));
    assertThatThrownBy(() -> guard().requireAdmin())
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void allowlistedAdmin_isPermitted() {
    UUID admin = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(admin));
    assertThatCode(() -> guard(admin).requireAdmin()).doesNotThrowAnyException();
  }

  @Test
  void adminProperties_nullAndEmptyHandling() {
    assertThat(new AdminAccessProperties(null).userIds()).isEmpty();
    UUID a = UUID.randomUUID();
    AdminAccessProperties props = new AdminAccessProperties(List.of(a));
    assertThat(props.isAdmin(a)).isTrue();
    assertThat(props.isAdmin(UUID.randomUUID())).isFalse();
    assertThat(props.isAdmin(null)).isFalse();
  }
}
