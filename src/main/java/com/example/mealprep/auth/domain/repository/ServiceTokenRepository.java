package com.example.mealprep.auth.domain.repository;

import com.example.mealprep.auth.domain.entity.ServiceToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link ServiceToken}. */
public interface ServiceTokenRepository extends JpaRepository<ServiceToken, UUID> {

  /**
   * Lookup the live ({@code enabled = true AND revoked_at IS NULL}) token row matching the SHA-256
   * hex hash. The single hot read on every Bearer-token request; backed by {@code
   * idx_auth_service_tokens_enabled}.
   */
  Optional<ServiceToken> findByTokenHashAndEnabledTrueAndRevokedAtIsNull(String tokenHash);

  /**
   * Bump {@code last_used_at} without touching {@code @Version} — fire-and-forget from the auth
   * provider, in a separate transaction, so a transient DB hiccup never blocks the inbound request.
   * Returns affected rows so the caller can log unexpected zero-updates.
   */
  @Modifying
  @Query("update ServiceToken t set t.lastUsedAt = :now where t.id = :id")
  int updateLastUsedAt(@Param("id") UUID id, @Param("now") Instant now);
}
