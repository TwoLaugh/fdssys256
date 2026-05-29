package com.example.mealprep.core.lock.internal;

import com.example.mealprep.core.lock.LockKey;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Postgres transaction-scoped advisory-lock operations — the {@code tryAcquire} half of {@code
 * com.example.mealprep.core.lock.LockService}, delegated to from {@code LockServiceImpl}.
 *
 * <p>Uses {@code pg_try_advisory_xact_lock(bigint)} — transaction-scoped, auto-released on commit
 * or rollback, no explicit release call needed.
 *
 * <p><strong>Must be called within an active transaction</strong>; without one the advisory lock
 * would be acquired and immediately released as Postgres has no transaction to bind it to. The
 * implementation throws {@link IllegalStateException} via the {@code @Transactional(MANDATORY)}
 * propagation rather than acquiring a meaningless lock.
 *
 * <p>A dedicated bean (not folded into {@code LockServiceImpl}) so the {@code @Transactional}
 * advice fires when {@code LockServiceImpl} delegates across the bean boundary, exactly as before
 * the lease methods were added.
 */
@Component
public class AdvisoryLockServiceImpl {

  @PersistenceContext private EntityManager entityManager;

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public boolean tryAcquire(LockKey key) {
    if (key == null) {
      throw new IllegalArgumentException("key must not be null");
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "tryAcquire must be called within an active transaction; advisory locks are tx-scoped");
    }
    long hash = LockKeyHasher.hash(key);
    Object result =
        entityManager
            .createNativeQuery("SELECT pg_try_advisory_xact_lock(:hash)")
            .setParameter("hash", hash)
            .getSingleResult();
    return Boolean.TRUE.equals(result);
  }
}
