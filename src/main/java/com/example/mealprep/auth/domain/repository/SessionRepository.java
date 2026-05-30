package com.example.mealprep.auth.domain.repository;

import com.example.mealprep.auth.domain.entity.Session;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link Session}. */
public interface SessionRepository extends JpaRepository<Session, UUID> {

  Optional<Session> findByTokenHash(String tokenHash);

  List<Session> findByUserIdAndRevokedAtIsNull(UUID userId);

  /**
   * Bulk-revoke every active session for a user. Used by password change to evict every session the
   * user opened from elsewhere; the calling session is re-issued separately so the user is not
   * bounced out.
   */
  @Modifying
  @Query(
      "update Session s set s.revokedAt = :now"
          + " where s.userId = :userId and s.revokedAt is null and s.id <> :exceptId")
  int revokeAllActiveForUserExcept(
      @Param("userId") UUID userId, @Param("exceptId") UUID exceptId, @Param("now") Instant now);

  @Modifying
  @Query(
      "update Session s set s.revokedAt = :now"
          + " where s.userId = :userId and s.revokedAt is null")
  int revokeAllActiveForUser(@Param("userId") UUID userId, @Param("now") Instant now);

  /**
   * Revoke a single still-active session in one statement. Used by the authentication filter's
   * best-effort soft-deleted-user cleanup (auth-6) — runs in its own transaction so a failure never
   * affects the inbound request.
   */
  @Modifying
  @Query("update Session s set s.revokedAt = :now where s.id = :id and s.revokedAt is null")
  int revokeById(@Param("id") UUID id, @Param("now") Instant now);

  /** Reaper-friendly bulk delete; not exposed to controllers. */
  @Modifying
  @Query(
      "delete from Session s where s.expiresAt < :cutoff"
          + " or (s.revokedAt is not null and s.revokedAt < :cutoff)")
  int deleteExpiredAndRevokedBefore(@Param("cutoff") Instant cutoff);
}
