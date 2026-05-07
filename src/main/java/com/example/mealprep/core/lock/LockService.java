package com.example.mealprep.core.lock;

/**
 * Single-flight lock primitive backed by Postgres advisory locks.
 *
 * <p>Locks are <strong>transaction-scoped</strong> via {@code pg_try_advisory_xact_lock} and
 * auto-release on transaction commit or rollback. There is no {@code release} method — callers
 * manage the lock lifecycle by managing the transaction.
 *
 * <p>Typical use:
 *
 * <pre>{@code
 * @Transactional
 * public void generatePlan(UUID householdId, LocalDate weekStart) {
 *   if (!lockService.tryAcquire(LockKey.forPlanWeek(householdId, weekStart))) {
 *     throw new AlreadyInProgressException(...);
 *   }
 *   // ... do work; lock auto-releases on commit/rollback
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
}
