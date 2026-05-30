package com.example.mealprep.core.lock.domain.repository;

import com.example.mealprep.core.lock.domain.entity.LockLease;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link LockLease}. Lives in {@code core.lock.domain.repository} per
 * the module-layout convention (repositories live in {@code <module>.domain.repository}); {@code
 * CoreBoundaryTest} forbids other modules importing it — cross-module callers go through {@code
 * com.example.mealprep.core.lock.LockService}.
 */
public interface LockLeaseRepository extends JpaRepository<LockLease, String> {

  /**
   * Claim a key by inserting a fresh lease, doing nothing if the row already exists. Native {@code
   * INSERT ... ON CONFLICT (lock_key) DO NOTHING} so the single-flight gate is the {@code lock_key}
   * primary key: exactly one concurrent insert wins (returns 1), the rest are no-ops (return 0).
   *
   * <p>A native insert (not {@code JpaRepository.save}) is required: {@code save} on an entity with
   * an application-assigned id does a SELECT-then-INSERT-or-UPDATE, which would silently OVERWRITE
   * a live lease instead of failing — defeating single-flight. {@code ON CONFLICT DO NOTHING} makes
   * "row already present" an unambiguous 0-rows-affected.
   *
   * @return 1 if this call inserted the lease, 0 if a row already existed
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO core_lock_leases (lock_key, holder_token, acquired_at, expires_at)
          VALUES (:lockKey, :token, :acquiredAt, :expiresAt)
          ON CONFLICT (lock_key) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("lockKey") String lockKey,
      @Param("token") UUID token,
      @Param("acquiredAt") Instant acquiredAt,
      @Param("expiresAt") Instant expiresAt);

  /**
   * Conditionally reclaim an existing lease whose {@code expires_at} has already passed. Atomic:
   * the {@code WHERE expires_at < :now} predicate means at most one concurrent caller flips the row
   * to its own token (the row-level write lock serialises them; the loser sees 0 rows affected
   * because the winner pushed {@code expires_at} into the future). A live (un-expired) lease is
   * never touched — the caller treats 0 rows as "contended".
   *
   * @return number of rows updated (1 = reclaimed, 0 = lease is still live or absent)
   */
  @Modifying
  @Query(
      """
      UPDATE LockLease l
         SET l.holderToken = :token,
             l.acquiredAt = :now,
             l.expiresAt = :expiresAt
       WHERE l.lockKey = :lockKey
         AND l.expiresAt < :now
      """)
  int reclaimIfExpired(
      @Param("lockKey") String lockKey,
      @Param("token") UUID token,
      @Param("now") Instant now,
      @Param("expiresAt") Instant expiresAt);

  /**
   * Delete the lease only when held by {@code (lockKey, holderToken)}. A non-holder release (a
   * stale handle whose lease was reclaimed by someone else) matches no row and is a silent no-op —
   * it must never delete the current owner's lease.
   *
   * @return number of rows deleted (1 = released, 0 = not the holder / already gone)
   */
  @Modifying
  @Query("DELETE FROM LockLease l WHERE l.lockKey = :lockKey AND l.holderToken = :token")
  int deleteByLockKeyAndHolderToken(@Param("lockKey") String lockKey, @Param("token") UUID token);

  /**
   * Extend the lease only when still held by {@code (lockKey, holderToken)}. A renew on a lease
   * that was already reclaimed by another holder matches no row.
   *
   * @return number of rows updated (1 = renewed, 0 = no longer the holder)
   */
  @Modifying
  @Query(
      """
      UPDATE LockLease l
         SET l.expiresAt = :expiresAt
       WHERE l.lockKey = :lockKey
         AND l.holderToken = :token
      """)
  int renewByLockKeyAndHolderToken(
      @Param("lockKey") String lockKey,
      @Param("token") UUID token,
      @Param("expiresAt") Instant expiresAt);
}
