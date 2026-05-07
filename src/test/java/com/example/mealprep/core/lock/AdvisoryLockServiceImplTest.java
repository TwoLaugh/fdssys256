package com.example.mealprep.core.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.lock.internal.AdvisoryLockServiceImpl;
import com.example.mealprep.core.lock.internal.LockKeyHasher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit tests for {@link AdvisoryLockServiceImpl}.
 *
 * <p>The {@code @Transactional(MANDATORY)} guard is enforced by Spring's AOP at runtime — these
 * tests exercise the bean directly so the guard is replaced by an in-method call to {@link
 * TransactionSynchronizationManager#isActualTransactionActive()}, which we mock statically. The
 * end-to-end propagation behaviour is covered by {@code AdvisoryLockServiceIT}.
 */
@ExtendWith(MockitoExtension.class)
class AdvisoryLockServiceImplTest {

  @Mock private EntityManager entityManager;
  @Mock private Query nativeQuery;

  private AdvisoryLockServiceImpl service;

  @BeforeEach
  void setUp() throws Exception {
    service = new AdvisoryLockServiceImpl();
    Field emField = AdvisoryLockServiceImpl.class.getDeclaredField("entityManager");
    emField.setAccessible(true);
    emField.set(service, entityManager);
  }

  @Test
  void tryAcquire_throws_whenKeyIsNull() {
    assertThatThrownBy(() -> service.tryAcquire(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be null");
  }

  @Test
  void tryAcquire_throws_whenNoActiveTransaction() {
    LockKey key = LockKey.forRecipe(UUID.randomUUID());
    try (MockedStatic<TransactionSynchronizationManager> tsm =
        mockStatic(TransactionSynchronizationManager.class)) {
      tsm.when(TransactionSynchronizationManager::isActualTransactionActive).thenReturn(false);

      assertThatThrownBy(() -> service.tryAcquire(key))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active transaction");
    }
  }

  @Test
  void tryAcquire_returnsTrue_whenPostgresReportsLockAcquired() {
    LockKey key = LockKey.forRecipe(UUID.randomUUID());
    long expectedHash = LockKeyHasher.hash(key);

    when(entityManager.createNativeQuery("SELECT pg_try_advisory_xact_lock(:hash)"))
        .thenReturn(nativeQuery);
    when(nativeQuery.setParameter("hash", expectedHash)).thenReturn(nativeQuery);
    when(nativeQuery.getSingleResult()).thenReturn(Boolean.TRUE);

    try (MockedStatic<TransactionSynchronizationManager> tsm =
        mockStatic(TransactionSynchronizationManager.class)) {
      tsm.when(TransactionSynchronizationManager::isActualTransactionActive).thenReturn(true);

      assertThat(service.tryAcquire(key)).isTrue();
    }

    verify(entityManager, times(1)).createNativeQuery("SELECT pg_try_advisory_xact_lock(:hash)");
    verify(nativeQuery).setParameter(eq("hash"), eq(expectedHash));
  }

  @Test
  void tryAcquire_returnsFalse_whenPostgresReportsLockUnavailable() {
    LockKey key = LockKey.forRecipe(UUID.randomUUID());
    long expectedHash = LockKeyHasher.hash(key);

    when(entityManager.createNativeQuery("SELECT pg_try_advisory_xact_lock(:hash)"))
        .thenReturn(nativeQuery);
    when(nativeQuery.setParameter("hash", expectedHash)).thenReturn(nativeQuery);
    when(nativeQuery.getSingleResult()).thenReturn(Boolean.FALSE);

    try (MockedStatic<TransactionSynchronizationManager> tsm =
        mockStatic(TransactionSynchronizationManager.class)) {
      tsm.when(TransactionSynchronizationManager::isActualTransactionActive).thenReturn(true);

      assertThat(service.tryAcquire(key)).isFalse();
    }
  }

  @Test
  void tryAcquire_returnsFalse_whenPostgresReturnsNull() {
    LockKey key = LockKey.forRecipe(UUID.randomUUID());

    when(entityManager.createNativeQuery("SELECT pg_try_advisory_xact_lock(:hash)"))
        .thenReturn(nativeQuery);
    when(nativeQuery.setParameter(eq("hash"), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(nativeQuery);
    when(nativeQuery.getSingleResult()).thenReturn(null);

    try (MockedStatic<TransactionSynchronizationManager> tsm =
        mockStatic(TransactionSynchronizationManager.class)) {
      tsm.when(TransactionSynchronizationManager::isActualTransactionActive).thenReturn(true);

      assertThat(service.tryAcquire(key)).isFalse();
    }
  }
}
