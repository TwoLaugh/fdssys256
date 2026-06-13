package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.api.controller.AuthController;
import com.example.mealprep.auth.api.dto.CurrentUserDto;
import com.example.mealprep.auth.api.dto.UserDto;
import com.example.mealprep.auth.config.AdminAccessProperties;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.service.AuthQueryService;
import com.example.mealprep.auth.domain.service.AuthUpdateService;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit coverage for the {@code GET /auth/me} admin-allowlist flag (frontend-gaps P3, admin page
 * spec §5). The allowlisted=true branch cannot be exercised in the shared-context IT (the allowlist
 * is bound from configuration at startup), so both branches are pinned here against a
 * directly-constructed {@link AdminAccessProperties}.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerMeTest {

  @Mock private AuthUpdateService authUpdateService;
  @Mock private AuthQueryService authQueryService;
  @Mock private CurrentUserResolver currentUserResolver;

  private final AuthProperties authProperties =
      new AuthProperties(null, null, null, null, null, null, null, null, null, null, null);

  private AuthController controller(AdminAccessProperties adminAccess) {
    return new AuthController(
        authUpdateService, authQueryService, currentUserResolver, authProperties, adminAccess);
  }

  private UUID stubAuthenticatedUser() {
    UUID userId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(userId));
    when(authQueryService.getUser(userId))
        .thenReturn(
            Optional.of(new UserDto(userId, "alice", Instant.parse("2026-06-01T00:00:00Z"))));
    return userId;
  }

  @Test
  void me_allowlistedUser_carriesIsAdminTrue() {
    UUID userId = stubAuthenticatedUser();

    CurrentUserDto dto = controller(new AdminAccessProperties(List.of(userId))).me();

    assertThat(dto.isAdmin()).isTrue();
    assertThat(dto.userId()).isEqualTo(userId);
    assertThat(dto.username()).isEqualTo("alice");
  }

  @Test
  void me_nonAllowlistedUser_carriesIsAdminFalse() {
    stubAuthenticatedUser();

    // Someone ELSE is on the allowlist — the caller still is not.
    CurrentUserDto dto = controller(new AdminAccessProperties(List.of(UUID.randomUUID()))).me();

    assertThat(dto.isAdmin()).isFalse();
  }

  @Test
  void me_unauthenticated_still401_beforeAnyAllowlistRead() {
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller(new AdminAccessProperties(List.of())).me())
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("401");
  }
}
