package com.example.mealprep.core.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Testcontainers-backed integration test for the Postgres advisory-lock service.
 *
 * <p>Verifies the contract from {@code lld/core.md §LockService}:
 *
 * <ul>
 *   <li>{@code tryAcquire} requires an active transaction
 *   <li>Same key contended from a separate concurrent transaction returns false
 *   <li>Lock auto-releases on commit — a third acquire of the same key succeeds
 *   <li>Lock auto-releases on rollback — same outcome via rollback
 *   <li>Different keys never contend
 * </ul>
 */
@SpringBootTest
@Import(TestContainersConfig.class)
class AdvisoryLockServiceIT {

  @Autowired private LockService lockService;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void tryAcquire_throws_whenNoActiveTransaction() {
    LockKey key = LockKey.forRecipe(UUID.randomUUID());
    assertThatThrownBy(() -> lockService.tryAcquire(key))
        .isInstanceOf(Exception.class)
        .satisfies(
            ex -> {
              // The @Transactional(MANDATORY) wrapper throws either
              // IllegalTransactionStateException (Spring) or our own IllegalStateException
              // depending on whether AOP intercepts before our guard fires. Either is fine —
              // the contract is "no active tx => no acquire."
              assertThat(ex.getClass().getName()).contains("Transaction");
            });
  }

  @Test
  void tryAcquire_returnsTrue_whenLockAvailable() {
    LockKey key = LockKey.forRecipe(UUID.randomUUID());
    Boolean acquired = newTxTemplate().execute(status -> lockService.tryAcquire(key));
    assertThat(acquired).isTrue();
  }

  @Test
  void tryAcquire_returnsFalse_whenAnotherTxHoldsTheSameKey() throws Exception {
    LockKey key = LockKey.forPlanWeek(UUID.randomUUID(), LocalDate.of(2026, 6, 1));
    CountDownLatch firstAcquired = new CountDownLatch(1);
    CountDownLatch firstShouldRelease = new CountDownLatch(1);

    // Background thread: acquire the lock, signal, then wait until the test releases us.
    CompletableFuture<Boolean> firstHolder =
        CompletableFuture.supplyAsync(
            () ->
                newTxTemplate()
                    .execute(
                        status -> {
                          boolean ok = lockService.tryAcquire(key);
                          firstAcquired.countDown();
                          await(firstShouldRelease);
                          return ok;
                        }));

    assertThat(firstAcquired.await(5, TimeUnit.SECONDS)).isTrue();

    // Main thread, separate tx: the same key should be unavailable.
    Boolean secondAttempt = newTxTemplate().execute(status -> lockService.tryAcquire(key));
    assertThat(secondAttempt).isFalse();

    // Let the holder commit and release.
    firstShouldRelease.countDown();
    assertThat(firstHolder.get(5, TimeUnit.SECONDS)).isTrue();

    // After commit, the lock is free again — third attempt succeeds.
    Boolean thirdAttempt = newTxTemplate().execute(status -> lockService.tryAcquire(key));
    assertThat(thirdAttempt).isTrue();
  }

  @Test
  void tryAcquire_locksAutoRelease_onRollback() throws Exception {
    LockKey key = LockKey.forCustom("test-rollback", UUID.randomUUID());
    CountDownLatch firstAcquired = new CountDownLatch(1);
    CountDownLatch firstShouldRollback = new CountDownLatch(1);

    CompletableFuture<Boolean> firstHolder =
        CompletableFuture.supplyAsync(
            () ->
                newTxTemplate()
                    .execute(
                        status -> {
                          boolean ok = lockService.tryAcquire(key);
                          firstAcquired.countDown();
                          await(firstShouldRollback);
                          status.setRollbackOnly();
                          return ok;
                        }));

    assertThat(firstAcquired.await(5, TimeUnit.SECONDS)).isTrue();

    // Holder is rolling back rather than committing.
    firstShouldRollback.countDown();
    assertThat(firstHolder.get(5, TimeUnit.SECONDS)).isTrue();

    // Lock should be released by the rollback — fresh acquire succeeds.
    Boolean afterRollback = newTxTemplate().execute(status -> lockService.tryAcquire(key));
    assertThat(afterRollback).isTrue();
  }

  @Test
  void tryAcquire_differentKeys_doNotContend() throws Exception {
    LockKey keyA = LockKey.forRecipe(UUID.randomUUID());
    LockKey keyB = LockKey.forRecipe(UUID.randomUUID());
    CountDownLatch aAcquired = new CountDownLatch(1);
    CountDownLatch aShouldRelease = new CountDownLatch(1);

    CompletableFuture<Boolean> holderA =
        CompletableFuture.supplyAsync(
            () ->
                newTxTemplate()
                    .execute(
                        status -> {
                          boolean ok = lockService.tryAcquire(keyA);
                          aAcquired.countDown();
                          await(aShouldRelease);
                          return ok;
                        }));

    assertThat(aAcquired.await(5, TimeUnit.SECONDS)).isTrue();

    // While A is held, B from a different tx should still acquire cleanly.
    Boolean bAcquired = newTxTemplate().execute(status -> lockService.tryAcquire(keyB));
    assertThat(bAcquired).isTrue();

    aShouldRelease.countDown();
    assertThat(holderA.get(5, TimeUnit.SECONDS)).isTrue();
  }

  private TransactionTemplate newTxTemplate() {
    TransactionTemplate template = new TransactionTemplate(txManager);
    template.setPropagationBehavior(Propagation.REQUIRED.value());
    return template;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Latch await timed out");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
