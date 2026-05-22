package com.example.mealprep.auth.security;

import com.example.mealprep.auth.domain.entity.ServiceToken;
import com.example.mealprep.auth.domain.repository.ServiceTokenRepository;
import com.example.mealprep.auth.domain.service.AuthenticatedPrincipal;
import com.example.mealprep.core.origin.Origin;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticates Pattern-B requests: {@code Authorization: Bearer <opaque-token>} where the token is
 * a service token from {@code auth_service_tokens}. Looks the token up by SHA-256 hash, validates
 * enabled + not-revoked, and returns an {@link Authentication} whose principal is the {@link
 * AuthenticatedPrincipal} for the {@code X-Acting-As} user — with one {@code ROLE_SERVICE_<origin>}
 * authority per permitted origin on the token.
 *
 * <p>Called directly by {@link com.example.mealprep.core.origin.OriginFilter}; <b>not registered as
 * an {@code AuthenticationProvider} on the global {@code AuthenticationManager}</b> because the
 * existing session-cookie chain is the default authentication path and the bearer path is parallel,
 * activated only by the origin filter when it observes the headers. Keeping it off the global
 * manager avoids the auth chain trying bearer tokens for every request.
 *
 * <p>{@code last_used_at} is bumped async + fire-and-forget so a transient DB error there never
 * blocks the request.
 */
public class ServiceTokenAuthenticationProvider {

  private static final Logger log =
      LoggerFactory.getLogger(ServiceTokenAuthenticationProvider.class);

  private final ServiceTokenRepository repository;
  private final Clock clock;

  public ServiceTokenAuthenticationProvider(ServiceTokenRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /**
   * Authenticate a bearer token + acting-as user against the {@code auth_service_tokens} table.
   *
   * @param rawToken plaintext bearer token (will be hashed before lookup)
   * @param actingAsUserId user the caller intends to act as (from {@code X-Acting-As})
   * @param claimedOrigin the {@code X-Origin} the request carries — must be in the token's
   *     whitelist
   * @return Spring {@link Authentication} with the user as principal + service-role authorities
   * @throws BadCredentialsException for unknown / disabled / revoked tokens
   * @throws OriginNotPermittedByTokenException when the claimed origin isn't in the token's {@code
   *     permitted_origins}
   */
  public Authentication authenticate(String rawToken, UUID actingAsUserId, Origin claimedOrigin) {
    if (rawToken == null || rawToken.isBlank()) {
      throw new BadCredentialsException("Bearer token must not be empty.");
    }
    String hash = sha256Hex(rawToken);
    Optional<ServiceToken> tokenOpt =
        repository.findByTokenHashAndEnabledTrueAndRevokedAtIsNull(hash);
    if (tokenOpt.isEmpty()) {
      // Lookup-by-hash already filters enabled + revoked_at IS NULL; a miss here covers all three
      // failure modes (unknown / disabled / revoked) and we DELIBERATELY do not distinguish them
      // in the response — a leak would tell an attacker which tokens once existed.
      throw new BadCredentialsException("Service token invalid, disabled, or revoked.");
    }
    ServiceToken token = tokenOpt.get();

    // Check the claimed X-Origin is in the token's whitelist. Stored as String[] (enum.name())
    // for forward-compatibility with new Origin values landing without an entity migration.
    String claimedName = claimedOrigin.name();
    boolean permitted = false;
    for (String permittedName : token.getPermittedOrigins()) {
      if (claimedName.equals(permittedName)) {
        permitted = true;
        break;
      }
    }
    if (!permitted) {
      throw new OriginNotPermittedByTokenException(claimedOrigin, token.getName());
    }

    // Build the authentication. Principal is AuthenticatedPrincipal of the X-Acting-As user (with
    // sessionId=null — there is no session under Pattern B). Authorities include ROLE_USER (so
    // user-scoped endpoints accept the call) plus one ROLE_SERVICE_<origin> per permitted origin.
    List<GrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
    for (String permittedName : token.getPermittedOrigins()) {
      authorities.add(new SimpleGrantedAuthority("ROLE_SERVICE_" + permittedName));
    }
    AuthenticatedPrincipal principal = new AuthenticatedPrincipal(actingAsUserId, null);
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(principal, null, authorities);

    // Fire-and-forget last_used_at bump. Inside the test harness this completes synchronously
    // (CompletableFuture.runAsync uses the common pool); production runs the same path. The
    // separate-tx propagation guarantees a failure here cannot poison the inbound auth flow.
    UUID tokenId = token.getId();
    Instant now = Instant.now(clock);
    CompletableFuture.runAsync(() -> bumpLastUsedAt(tokenId, now))
        .exceptionally(
            t -> {
              log.warn("service-token last_used_at bump failed (token={})", tokenId, t);
              return null;
            });

    return auth;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  protected void bumpLastUsedAt(UUID tokenId, Instant now) {
    repository.updateLastUsedAt(tokenId, now);
  }

  /**
   * SHA-256 hex of the raw token's UTF-8 bytes — same convention as {@code SessionTokenGenerator}
   * so the hash columns on {@code auth_sessions} and {@code auth_service_tokens} share a format. 64
   * hex chars; column width 96 leaves head-room if a future ticket switches to bcrypt (60-char
   * output) without a schema migration.
   *
   * <p>Exposed publicly so callers minting service tokens (admin CLI, tests building fixtures) can
   * compute the same hash this provider compares against.
   */
  public static String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /**
   * Thrown when the bearer token successfully resolves but the requested {@code X-Origin} is not in
   * its {@code permitted_origins} whitelist.
   *
   * <p>Mapped to HTTP 403 by {@link com.example.mealprep.config.GlobalExceptionHandler}.
   */
  public static class OriginNotPermittedByTokenException extends RuntimeException {

    private final Origin claimedOrigin;
    private final String tokenName;

    public OriginNotPermittedByTokenException(Origin claimedOrigin, String tokenName) {
      super(
          "Service token '"
              + tokenName
              + "' is not permitted to claim origin "
              + claimedOrigin
              + ".");
      this.claimedOrigin = claimedOrigin;
      this.tokenName = tokenName;
    }

    public Origin getClaimedOrigin() {
      return claimedOrigin;
    }

    public String getTokenName() {
      return tokenName;
    }
  }
}
