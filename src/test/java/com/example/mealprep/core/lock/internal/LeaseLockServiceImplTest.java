package com.example.mealprep.core.lock.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.lock.LeaseHandle;
import com.example.mealprep.core.lock.LockKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link LeaseLockServiceImpl} — the connection-free TTL lease branch of {@code
 * LockService}. The {@code @Transactional(REQUIRES_NEW)} boundaries are exercised end-to-end by
 * {@code LeaseLockServiceIT}; here the repository is mocked so the acquire/reclaim/release/renew
 * branch logic is asserted directly.
 */
@ExtendWith(MockitoExtension.class)
class LeaseLockServiceImplTest {

  private static final Instant NOW = Instant.parse("2026-05-29T12:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final LockKey KEY =
      LockKey.forPlanWeek(UUID.randomUUID(), java.time.LocalDate.of(2026, 6, 1));
  private static final Duration TTL = Duration.ofMinutes(10);

  @Mock private LockLeaseRepository repository;
  private LeaseLockServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new LeaseLockServiceImpl(repository, FIXED);
  }

  @Test
  void acquireLease_returnsHandle_whenInsertSucceeds() {
    when(repository.insertIfAbsent(eq(KEY.serialize()), any(), eq(NOW), eq(NOW.plus(TTL))))
        .thenReturn(1);

    Optional<LeaseHandle> handle = service.acquireLease(KEY, TTL);

    assertThat(handle).isPresent();
    assertThat(handle.get().key()).isEqualTo(KEY);
    assertThat(handle.get().acquiredAt()).isEqualTo(NOW);
    assertThat(handle.get().expiresAt()).isEqualTo(NOW.plus(TTL));
    // No reclaim attempted on the happy INSERT path.
    verify(repository, never()).reclaimIfExpired(anyString(), any(), any(), any());
  }

  @Test
  void acquireLease_reclaims_whenExistingLeaseExpired() {
    when(repository.insertIfAbsent(eq(KEY.serialize()), any(), eq(NOW), eq(NOW.plus(TTL))))
        .thenReturn(0);
    when(repository.reclaimIfExpired(eq(KEY.serialize()), any(), eq(NOW), eq(NOW.plus(TTL))))
        .thenReturn(1);

    Optional<LeaseHandle> handle = service.acquireLease(KEY, TTL);

    assertThat(handle).isPresent();
    assertThat(handle.get().expiresAt()).isEqualTo(NOW.plus(TTL));
  }

  @Test
  void acquireLease_returnsEmpty_whenLiveLeaseHeldByAnother() {
    when(repository.insertIfAbsent(eq(KEY.serialize()), any(), eq(NOW), eq(NOW.plus(TTL))))
        .thenReturn(0);
    when(repository.reclaimIfExpired(eq(KEY.serialize()), any(), eq(NOW), eq(NOW.plus(TTL))))
        .thenReturn(0);

    assertThat(service.acquireLease(KEY, TTL)).isEmpty();
  }

  @Test
  void acquireLease_throws_onNullKey() {
    assertThatThrownBy(() -> service.acquireLease(null, TTL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key");
  }

  @Test
  void acquireLease_throws_onNonPositiveTtl() {
    assertThatThrownBy(() -> service.acquireLease(KEY, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ttl");
    assertThatThrownBy(() -> service.acquireLease(KEY, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ttl");
    assertThatThrownBy(() -> service.acquireLease(KEY, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ttl");
  }

  @Test
  void releaseLease_returnsTrue_whenHolderRowDeleted() {
    UUID token = UUID.randomUUID();
    LeaseHandle handle = new LeaseHandle(KEY, token, NOW, NOW.plus(TTL));
    when(repository.deleteByLockKeyAndHolderToken(KEY.serialize(), token)).thenReturn(1);

    assertThat(service.releaseLease(handle)).isTrue();
  }

  @Test
  void releaseLease_returnsFalse_andDeletesNothing_whenNotHolder() {
    UUID token = UUID.randomUUID();
    LeaseHandle handle = new LeaseHandle(KEY, token, NOW, NOW.plus(TTL));
    // Someone reclaimed our expired lease: our (key, token) matches no row.
    when(repository.deleteByLockKeyAndHolderToken(KEY.serialize(), token)).thenReturn(0);

    assertThat(service.releaseLease(handle)).isFalse();
  }

  @Test
  void releaseLease_throws_onNullHandle() {
    assertThatThrownBy(() -> service.releaseLease(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("handle");
  }

  @Test
  void renewLease_returnsRefreshedHandle_whenStillHolder() {
    UUID token = UUID.randomUUID();
    Instant originalAcquired = NOW.minus(Duration.ofMinutes(2));
    LeaseHandle handle = new LeaseHandle(KEY, token, originalAcquired, NOW);
    when(repository.renewByLockKeyAndHolderToken(KEY.serialize(), token, NOW.plus(TTL)))
        .thenReturn(1);

    Optional<LeaseHandle> renewed = service.renewLease(handle, TTL);

    assertThat(renewed).isPresent();
    assertThat(renewed.get().holderToken()).isEqualTo(token);
    assertThat(renewed.get().acquiredAt()).isEqualTo(originalAcquired);
    assertThat(renewed.get().expiresAt()).isEqualTo(NOW.plus(TTL));
  }

  @Test
  void renewLease_returnsEmpty_whenNoLongerHolder() {
    UUID token = UUID.randomUUID();
    LeaseHandle handle = new LeaseHandle(KEY, token, NOW, NOW);
    when(repository.renewByLockKeyAndHolderToken(KEY.serialize(), token, NOW.plus(TTL)))
        .thenReturn(0);

    assertThat(service.renewLease(handle, TTL)).isEmpty();
  }

  @Test
  void renewLease_throws_onNullHandleOrBadTtl() {
    LeaseHandle handle = new LeaseHandle(KEY, UUID.randomUUID(), NOW, NOW);
    assertThatThrownBy(() -> service.renewLease(null, TTL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.renewLease(handle, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
