package com.example.mealprep.core.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.testsupport.TestContainersConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Testcontainers-backed integration test for the connection-free TTL lease lock added to {@code
 * core.LockService} (lld/core.md §LockService).
 *
 * <p>Verifies the lease contract against a real Postgres + the real Flyway-applied {@code
 * core_lock_leases} table:
 *
 * <ul>
 *   <li>acquire → contend (a second acquire on the same key returns empty while the first lease is
 *       live)
 *   <li>acquire → release → re-acquire (release frees the key; a fresh acquire succeeds)
 *   <li>acquire → expire (TTL in the past) → reclaim-by-other (a crashed holder's lease is
 *       reclaimed lazily on the next acquire)
 *   <li>release-by-non-holder is a no-op (a stale handle must not delete the current owner's lease)
 *   <li>renew extends a held lease; renew by a non-holder is a no-op
 *   <li>different keys never contend
 * </ul>
 *
 * <p>Each {@code acquireLease} runs in its own short {@code REQUIRES_NEW} transaction, so this test
 * (which holds no surrounding transaction) sees committed lease rows — the connection-free property
 * the planner relies on.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
class LeaseLockServiceIT {

  private static final Duration TTL = Duration.ofMinutes(10);

  @Autowired private LockService lockService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM core_lock_leases");
  }

  private LockKey freshKey() {
    return LockKey.forPlanWeek(UUID.randomUUID(), LocalDate.of(2026, 6, 1));
  }

  @Test
  void acquireLease_succeeds_thenContends_whileLeaseLive() {
    LockKey key = freshKey();

    Optional<LeaseHandle> first = lockService.acquireLease(key, TTL);
    assertThat(first).as("first acquire claims the key").isPresent();

    Optional<LeaseHandle> second = lockService.acquireLease(key, TTL);
    assertThat(second).as("second acquire on a live lease is contended").isEmpty();

    // The committed lease row exists for the key.
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_lock_leases WHERE lock_key = ?",
            Integer.class,
            key.serialize());
    assertThat(count).isEqualTo(1);
  }

  @Test
  void acquireLease_afterRelease_reacquireSucceeds() {
    LockKey key = freshKey();

    LeaseHandle handle = lockService.acquireLease(key, TTL).orElseThrow();
    assertThat(lockService.releaseLease(handle)).as("holder release removes the lease").isTrue();

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM core_lock_leases WHERE lock_key = ?",
                Integer.class,
                key.serialize()))
        .isZero();

    Optional<LeaseHandle> reacquired = lockService.acquireLease(key, TTL);
    assertThat(reacquired).as("re-acquire after release succeeds").isPresent();
  }

  @Test
  void acquireLease_reclaimsExpiredLease_byAnotherHolder() {
    LockKey key = freshKey();

    // Simulate a crashed holder: a committed lease row whose expires_at is already in the past.
    UUID crashedToken = UUID.randomUUID();
    Instant past = Instant.now().minus(Duration.ofMinutes(30));
    jdbcTemplate.update(
        "INSERT INTO core_lock_leases (lock_key, holder_token, acquired_at, expires_at)"
            + " VALUES (?, ?, ?, ?)",
        key.serialize(),
        crashedToken,
        Timestamp.from(past.minus(Duration.ofMinutes(10))),
        Timestamp.from(past));

    // Another caller acquires: the expired lease is reclaimed under a fresh token.
    Optional<LeaseHandle> reclaimed = lockService.acquireLease(key, TTL);
    assertThat(reclaimed).as("expired lease is reclaimable").isPresent();
    assertThat(reclaimed.get().holderToken())
        .as("reclaimer gets a fresh token, not the crashed holder's")
        .isNotEqualTo(crashedToken);

    UUID rowToken =
        jdbcTemplate.queryForObject(
            "SELECT holder_token FROM core_lock_leases WHERE lock_key = ?",
            UUID.class,
            key.serialize());
    assertThat(rowToken).isEqualTo(reclaimed.get().holderToken());
  }

  @Test
  void releaseLease_byNonHolder_isNoOp_andDoesNotEvictCurrentOwner() {
    LockKey key = freshKey();

    // Real owner acquires.
    LeaseHandle owner = lockService.acquireLease(key, TTL).orElseThrow();

    // A stale handle for the same key with a different token (e.g. a crashed holder whose lease was
    // reclaimed by 'owner'). Releasing it must not delete the owner's live lease.
    LeaseHandle stale =
        new LeaseHandle(key, UUID.randomUUID(), owner.acquiredAt(), owner.expiresAt());
    assertThat(lockService.releaseLease(stale)).as("non-holder release is a no-op").isFalse();

    // Owner's lease still present.
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM core_lock_leases WHERE lock_key = ? AND holder_token = ?",
                Integer.class,
                key.serialize(),
                owner.holderToken()))
        .isEqualTo(1);

    // And a new acquire is still contended (owner still holds it).
    assertThat(lockService.acquireLease(key, TTL)).isEmpty();
  }

  @Test
  void renewLease_extendsHeldLease_andNonHolderRenewIsNoOp() {
    LockKey key = freshKey();
    LeaseHandle handle = lockService.acquireLease(key, Duration.ofMinutes(5)).orElseThrow();

    Optional<LeaseHandle> renewed = lockService.renewLease(handle, Duration.ofMinutes(20));
    assertThat(renewed).isPresent();
    assertThat(renewed.get().expiresAt()).isAfter(handle.expiresAt());

    // A non-holder token cannot renew.
    LeaseHandle stale =
        new LeaseHandle(key, UUID.randomUUID(), handle.acquiredAt(), handle.expiresAt());
    assertThat(lockService.renewLease(stale, Duration.ofMinutes(20))).isEmpty();
  }

  @Test
  void acquireLease_differentKeys_doNotContend() {
    LockKey a = freshKey();
    LockKey b = freshKey();

    assertThat(lockService.acquireLease(a, TTL)).isPresent();
    assertThat(lockService.acquireLease(b, TTL))
        .as("a held lease on a different key never contends")
        .isPresent();
  }
}
