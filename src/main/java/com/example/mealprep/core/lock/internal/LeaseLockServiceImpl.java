package com.example.mealprep.core.lock.internal;

import com.example.mealprep.core.lock.LeaseHandle;
import com.example.mealprep.core.lock.LockKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connection-free TTL lease operations — the {@code acquireLease} / {@code releaseLease} / {@code
 * renewLease} half of {@code com.example.mealprep.core.lock.LockService}, delegated to from {@code
 * LockServiceImpl}.
 *
 * <p>Each method runs in its own short {@code @Transactional(REQUIRES_NEW)} transaction so the DB
 * connection is returned to the pool the instant the row write commits. The lease then lives as a
 * committed row in {@code core_lock_leases}, held across an arbitrarily long, connection-free
 * operation. A dedicated bean (separate from {@code LockServiceImpl}) so the {@code REQUIRES_NEW}
 * advice fires across the bean boundary even when the caller already has, or deliberately has no,
 * transaction.
 *
 * <h2>Acquire semantics — INSERT, then conditional reclaim</h2>
 *
 * <ol>
 *   <li>Attempt a fresh INSERT via {@code INSERT ... ON CONFLICT DO NOTHING}. The {@code lock_key}
 *       primary key makes this the single-flight gate: exactly one of N concurrent inserts for the
 *       same key affects a row (returns 1); the rest are no-ops (return 0).
 *   <li>On a 0-row insert (the row already exists), attempt an atomic conditional reclaim ({@code
 *       UPDATE ... WHERE expires_at < now}). This succeeds only if the existing lease has expired
 *       (a crashed holder), reclaiming it under a <em>fresh</em> holder token. A live lease updates
 *       0 rows ⇒ contended ⇒ {@code Optional.empty()}.
 * </ol>
 *
 * <p>Doing the reclaim as a guarded UPDATE (rather than read-then-write) keeps it atomic under
 * concurrency: the row write lock serialises racing reclaimers and the {@code expires_at < now}
 * predicate ensures only the first one past the post wins.
 */
@Component
public class LeaseLockServiceImpl {

  private static final Logger log = LoggerFactory.getLogger(LeaseLockServiceImpl.class);

  private final LockLeaseRepository repository;
  private final Clock clock;

  public LeaseLockServiceImpl(LockLeaseRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<LeaseHandle> acquireLease(LockKey key, Duration ttl) {
    if (key == null) {
      throw new IllegalArgumentException("key must not be null");
    }
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    String lockKey = key.serialize();
    UUID token = UUID.randomUUID();
    Instant now = clock.instant();
    Instant expiresAt = now.plus(ttl);

    // 1. Fast path: no row yet — claim it with INSERT ... ON CONFLICT DO NOTHING. The PK on
    // lock_key is the single-flight gate; 1 row affected means we won, 0 means a row already
    // exists.
    int inserted = repository.insertIfAbsent(lockKey, token, now, expiresAt);
    if (inserted == 1) {
      log.debug("Lease acquired (new) key={} token={} ttl={}", lockKey, token, ttl);
      return Optional.of(new LeaseHandle(key, token, now, expiresAt));
    }

    // 2. A lease row already exists. Reclaim it iff it has expired (lazy reclaim of a crashed
    // holder); a live lease leaves 0 rows updated ⇒ contended.
    int reclaimed = repository.reclaimIfExpired(lockKey, token, now, expiresAt);
    if (reclaimed == 1) {
      log.info(
          "Lease reclaimed from expired holder key={} newToken={} ttl={}", lockKey, token, ttl);
      return Optional.of(new LeaseHandle(key, token, now, expiresAt));
    }
    log.debug("Lease contended (live holder) key={}", lockKey);
    return Optional.empty();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean releaseLease(LeaseHandle handle) {
    if (handle == null) {
      throw new IllegalArgumentException("handle must not be null");
    }
    int deleted =
        repository.deleteByLockKeyAndHolderToken(handle.key().serialize(), handle.holderToken());
    if (deleted == 1) {
      log.debug("Lease released key={} token={}", handle.key().serialize(), handle.holderToken());
      return true;
    }
    // Not the holder (someone reclaimed our expired lease) or already gone — silent no-op so we
    // never delete the current owner's lease.
    log.debug(
        "Lease release no-op (not holder / already gone) key={} token={}",
        handle.key().serialize(),
        handle.holderToken());
    return false;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<LeaseHandle> renewLease(LeaseHandle handle, Duration ttl) {
    if (handle == null) {
      throw new IllegalArgumentException("handle must not be null");
    }
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    Instant now = clock.instant();
    Instant newExpiresAt = now.plus(ttl);
    int renewed =
        repository.renewByLockKeyAndHolderToken(
            handle.key().serialize(), handle.holderToken(), newExpiresAt);
    if (renewed == 1) {
      return Optional.of(
          new LeaseHandle(handle.key(), handle.holderToken(), handle.acquiredAt(), newExpiresAt));
    }
    log.debug("Lease renew no-op (no longer holder) key={}", handle.key().serialize());
    return Optional.empty();
  }
}
