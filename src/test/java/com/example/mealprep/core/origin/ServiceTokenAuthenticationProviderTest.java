package com.example.mealprep.core.origin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.domain.entity.ServiceToken;
import com.example.mealprep.auth.domain.repository.ServiceTokenRepository;
import com.example.mealprep.auth.domain.service.AuthenticatedPrincipal;
import com.example.mealprep.auth.security.ServiceTokenAuthenticationProvider;
import com.example.mealprep.auth.security.ServiceTokenAuthenticationProvider.OriginNotPermittedByTokenException;
import com.example.mealprep.core.origin.testdata.ServiceTokenTestData;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

/**
 * Pinning for {@link ServiceTokenAuthenticationProvider}. Covers: happy-path, hash-not-found,
 * origin-not-permitted, blank/null inputs.
 *
 * <p>The {@code last_used_at} bump is fire-and-forget (CompletableFuture.runAsync); we don't assert
 * on it because the common pool may not run the task within the test's lifetime. The authentication
 * return value is what matters.
 */
@ExtendWith(MockitoExtension.class)
class ServiceTokenAuthenticationProviderTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-05-21T10:00:00Z"), ZoneOffset.UTC);

  @Mock private ServiceTokenRepository repository;

  private ServiceTokenAuthenticationProvider provider;

  @BeforeEach
  void setUp() {
    provider = new ServiceTokenAuthenticationProvider(repository, FIXED_CLOCK);
    lenient().when(repository.updateLastUsedAt(any(UUID.class), any(Instant.class))).thenReturn(1);
  }

  @Test
  void authenticate_returns_authentication_with_acting_as_user_and_service_roles() {
    String rawToken = "my-plaintext-token-value";
    String hash = ServiceTokenAuthenticationProvider.sha256Hex(rawToken);
    ServiceToken token = ServiceTokenTestData.aLiveToken(hash, Origin.SYSTEM_SCHEDULED);
    UUID actingAs = UUID.randomUUID();
    when(repository.findByTokenHashAndEnabledTrueAndRevokedAtIsNull(hash))
        .thenReturn(Optional.of(token));

    Authentication auth = provider.authenticate(rawToken, actingAs, Origin.SYSTEM_SCHEDULED);

    assertThat(auth).isNotNull();
    assertThat(auth.getPrincipal()).isInstanceOf(AuthenticatedPrincipal.class);
    AuthenticatedPrincipal principal = (AuthenticatedPrincipal) auth.getPrincipal();
    assertThat(principal.userId()).isEqualTo(actingAs);
    assertThat(principal.sessionId()).isNull();
    assertThat(auth.getAuthorities())
        .extracting(a -> a.getAuthority())
        .contains("ROLE_USER", "ROLE_SERVICE_SYSTEM_SCHEDULED");
  }

  @Test
  void authenticate_throws_BadCredentials_when_token_not_found() {
    String rawToken = "ghost-token";
    when(repository.findByTokenHashAndEnabledTrueAndRevokedAtIsNull(anyString()))
        .thenReturn(Optional.empty());

    assertThatExceptionOfType(BadCredentialsException.class)
        .isThrownBy(
            () -> provider.authenticate(rawToken, UUID.randomUUID(), Origin.SYSTEM_SCHEDULED));
  }

  @Test
  void authenticate_throws_BadCredentials_when_token_is_blank() {
    assertThatExceptionOfType(BadCredentialsException.class)
        .isThrownBy(() -> provider.authenticate("   ", UUID.randomUUID(), Origin.SYSTEM_SCHEDULED));
  }

  @Test
  void authenticate_throws_BadCredentials_when_token_is_null() {
    assertThatExceptionOfType(BadCredentialsException.class)
        .isThrownBy(() -> provider.authenticate(null, UUID.randomUUID(), Origin.SYSTEM_SCHEDULED));
  }

  @Test
  void authenticate_rejects_origin_not_in_token_whitelist() {
    String rawToken = "scheduled-only-token";
    String hash = ServiceTokenAuthenticationProvider.sha256Hex(rawToken);
    ServiceToken token = ServiceTokenTestData.aLiveToken(hash, Origin.SYSTEM_SCHEDULED);
    when(repository.findByTokenHashAndEnabledTrueAndRevokedAtIsNull(hash))
        .thenReturn(Optional.of(token));

    // Token only permits SYSTEM_SCHEDULED — caller asks for AI_FEEDBACK → reject.
    assertThatExceptionOfType(OriginNotPermittedByTokenException.class)
        .isThrownBy(() -> provider.authenticate(rawToken, UUID.randomUUID(), Origin.AI_FEEDBACK))
        .satisfies(
            ex -> {
              assertThat(ex.getClaimedOrigin()).isEqualTo(Origin.AI_FEEDBACK);
              assertThat(ex.getTokenName()).isEqualTo(token.getName());
            });
  }

  @Test
  void sha256_hash_is_deterministic_and_64_hex_chars() {
    String hash1 = ServiceTokenAuthenticationProvider.sha256Hex("same-input");
    String hash2 = ServiceTokenAuthenticationProvider.sha256Hex("same-input");
    String hash3 = ServiceTokenAuthenticationProvider.sha256Hex("different-input");

    assertThat(hash1).isEqualTo(hash2).hasSize(64).matches("[0-9a-f]+");
    assertThat(hash3).isNotEqualTo(hash1);
  }
}
