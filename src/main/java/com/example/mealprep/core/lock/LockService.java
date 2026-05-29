package com.example.mealprep.core.lock;

import java.time.Duration;
import java.util.Optional;

/**
 * Single-flight lock primitives for the project. Two distinct mechanisms live here, for two
 * distinct shapes of work:
 *
 * <h2>1. Transaction-scoped advisory lock — {@link #tryAcquire}</h2>
 *
 * Backed by {@code pg_try_advisory_xact_lock}. Transaction-scoped: auto-releases on commit or
 * rollback, no explicit release. Held for the lifetime of the caller's transaction and no longer.
 * Used by short critical sections that already run inside one transaction (the adaptation
 * pipeline's per-recipe lock, grocery's per-list batch lock).
 *
 * <pre>{@code
 * @Transactional
 * public void process(UUID recipeId) {
 *   if (!lockService.tryAcquire(LockKey.forRecipe(recipeId))) {
 *     throw new AlreadyInProgressException(...);
 *   }
 *   // ... work; lock auto-releases on commit/rollback
 * }
 * }</pre>
 *
 * <h2>2. Connection-free TTL lease — {@link #acquireLease} / {@link #releaseLease} / {@link
 * #renewLease}</h2>
 *
 * A <strong>committed lease row</strong>, not a held lock. {@link #acquireLease} claims the key in
 * a short {@code REQUIRES_NEW} transaction (the connection is released the instant that transaction
 * commits) and returns a {@link LeaseHandle}; the lease then persists across an arbitrarily long
 * operation that holds <strong>no DB connection</strong>. Single-flight is enforced by the lease
 * row's existence under a unique key; the holder releases it via {@link #releaseLease} when the
 * operation finishes.
 *
 * <p>This is the only way to reject a concurrent operation <em>before</em> it does its (possibly
 * 20-second, connection-free) work without re-pinning a connection for that whole window — which is
 * exactly why the planner uses it to single-flight plan generation at the start of the AI pipeline,
 * not a {@link #tryAcquire} advisory lock (which would have to be held inside the AI transaction it
 * is forbidden to open).
 *
 * <p><strong>Liveness.</strong> A crashed holder never releases its lease, so every lease carries a
 * TTL ({@code expiresAt}). {@link #acquireLease} reclaims any lease whose TTL has passed (lazy
 * reclaim-on-acquire). Choose a TTL safely larger than the maximum operation time.
 *
 * <pre>{@code
 * Optional<LeaseHandle> lease = lockService.acquireLease(key, Duration.ofMinutes(10));
 * if (lease.isEmpty()) {
 *   throw new AlreadyInProgressException(...);   // contended — reject before doing work
 * }
 * try {
 *   doLongConnectionFreeWork();                  // holds no DB connection
 * } finally {
 *   lockService.releaseLease(lease.get());       // free immediately on success/failure
 * }
 * }</pre>
 */
public interface LockService {

  /**
   * Attempt to acquire the advisory lock identified by {@code key} within the current transaction.
   *
   * @param key the lock identity; never null
   * @return {@code true} if the lock was acquired (caller now holds it for the rest of the
   *     transaction); {@code false} if another transaction already holds it
   * @throws IllegalStateException if called outside an active transaction
   */
  boolean tryAcquire(LockKey key);

  /**
   * Attempt to claim a connection-free TTL lease on {@code key}. Runs in its own short {@code
   * REQUIRES_NEW} transaction that commits (and releases its connection) before returning, so the
   * lease can be held across a long operation without pinning a connection.
   *
   * <p>Succeeds when no live lease exists for {@code key}, OR an existing lease has expired (its
   * {@code expiresAt} is in the past) and is reclaimed atomically. Fails (returns {@code empty})
   * when another holder owns a live lease.
   *
   * @param key the lock identity; never null
   * @param ttl how long the lease is valid before it becomes reclaimable; must be positive
   * @return a {@link LeaseHandle} proving ownership, or {@code empty} on contention
   */
  Optional<LeaseHandle> acquireLease(LockKey key, Duration ttl);

  /**
   * Release a lease previously obtained from {@link #acquireLease}. Only the holder of the matching
   * {@code (lockKey, holderToken)} may release; a stale handle whose lease was reclaimed by another
   * caller releases nothing (silent no-op). Idempotent: releasing an already-released lease is a
   * no-op. Runs in its own short {@code REQUIRES_NEW} transaction.
   *
   * @param handle the handle returned by {@link #acquireLease}; never null
   * @return {@code true} if this call removed the lease, {@code false} if it was not the holder or
   *     the lease was already gone
   */
  boolean releaseLease(LeaseHandle handle);

  /**
   * Extend a still-held lease's TTL by {@code ttl} from now, returning a refreshed handle. Used by
   * operations that may outrun their initial TTL. Only the current holder can renew; a lease
   * already reclaimed by another caller returns {@code empty}. Runs in its own short {@code
   * REQUIRES_NEW} transaction.
   *
   * @param handle the handle returned by {@link #acquireLease}; never null
   * @param ttl the new validity window from now; must be positive
   * @return a refreshed {@link LeaseHandle}, or {@code empty} if no longer the holder
   */
  Optional<LeaseHandle> renewLease(LeaseHandle handle, Duration ttl);
}
