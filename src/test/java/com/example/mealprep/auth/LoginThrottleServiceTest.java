package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.entity.LoginAttempt;
import com.example.mealprep.auth.domain.entity.LoginFailureReason;
import com.example.mealprep.auth.domain.repository.LoginAttemptRepository;
import com.example.mealprep.auth.domain.service.internal.LoginThrottleService;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link LoginThrottleService}. Repository is mocked because the throttle logic is a
 * pure decision over a windowed count — Postgres semantics are exercised in {@code
 * ThrottlingAndLockoutIT}.
 */
@ExtendWith(MockitoExtension.class)
class LoginThrottleServiceTest {

  @Mock private LoginAttemptRepository repo;

  private final AuthProperties properties =
      new AuthProperties(null, null, null, null, null, null, null, null, null);
  private final Instant now = Instant.parse("2026-05-07T12:00:00Z");

  private LoginThrottleService service() {
    return new LoginThrottleService(repo, properties);
  }

  @Test
  void shouldThrottleByUsername_belowThreshold_returnsFalse() {
    when(repo.countByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonIn(
            eq("alice"), any(Instant.class), any(Collection.class)))
        .thenReturn(9L);

    assertThat(service().shouldThrottleByUsername("alice", now)).isFalse();
  }

  @Test
  void shouldThrottleByUsername_atThreshold_returnsTrue() {
    when(repo.countByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonIn(
            eq("alice"), any(Instant.class), any(Collection.class)))
        .thenReturn(10L);

    assertThat(service().shouldThrottleByUsername("alice", now)).isTrue();
  }

  @Test
  void shouldThrottleByUsername_aboveThreshold_returnsTrue() {
    when(repo.countByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonIn(
            eq("alice"), any(Instant.class), any(Collection.class)))
        .thenReturn(50L);

    assertThat(service().shouldThrottleByUsername("alice", now)).isTrue();
  }

  @Test
  void shouldThrottleByUsername_emptyUsername_returnsFalseAndDoesNotQueryDb() {
    LoginThrottleService service = service();

    assertThat(service.shouldThrottleByUsername("", now)).isFalse();
    assertThat(service.shouldThrottleByUsername(null, now)).isFalse();
    verify(repo, never())
        .countByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonIn(any(), any(), any());
  }

  @Test
  void shouldThrottleByIp_belowThreshold_returnsFalse() {
    when(repo.countBySourceIpAndAttemptedAtAfterAndFailureReasonIn(
            eq("203.0.113.5"), any(Instant.class), any(Collection.class)))
        .thenReturn(29L);

    assertThat(service().shouldThrottleByIp("203.0.113.5", now)).isFalse();
  }

  @Test
  void shouldThrottleByIp_atThreshold_returnsTrue() {
    when(repo.countBySourceIpAndAttemptedAtAfterAndFailureReasonIn(
            eq("203.0.113.5"), any(Instant.class), any(Collection.class)))
        .thenReturn(30L);

    assertThat(service().shouldThrottleByIp("203.0.113.5", now)).isTrue();
  }

  @Test
  void shouldThrottleByIp_emptyIp_returnsFalseAndDoesNotQueryDb() {
    LoginThrottleService service = service();

    assertThat(service.shouldThrottleByIp("", now)).isFalse();
    assertThat(service.shouldThrottleByIp(null, now)).isFalse();
    verify(repo, never()).countBySourceIpAndAttemptedAtAfterAndFailureReasonIn(any(), any(), any());
  }

  @Test
  void countedReasons_excludeAccountLockedAndThrottled() {
    when(repo.countByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonIn(
            eq("alice"), any(Instant.class), any(Collection.class)))
        .thenAnswer(
            inv -> {
              Collection<LoginFailureReason> reasons = inv.getArgument(2);
              assertThat(reasons)
                  .containsExactlyInAnyOrder(
                      LoginFailureReason.BAD_PASSWORD, LoginFailureReason.UNKNOWN_USER);
              assertThat(reasons).doesNotContain(LoginFailureReason.ACCOUNT_LOCKED);
              assertThat(reasons).doesNotContain(LoginFailureReason.THROTTLED);
              return 0L;
            });

    service().shouldThrottleByUsername("alice", now);
  }

  @Test
  void retryAfter_oldestAttemptDrivesWindow() {
    Instant oldest = now.minus(Duration.ofMinutes(10)); // 5 min remaining in 15-min window
    LoginAttempt row =
        LoginAttempt.builder()
            .id(UUID.randomUUID())
            .usernameNormalised("alice")
            .sourceIp("203.0.113.5")
            .succeeded(false)
            .failureReason(LoginFailureReason.BAD_PASSWORD)
            .attemptedAt(oldest)
            .build();
    when(repo.findByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonInOrderByAttemptedAtAsc(
            eq("alice"), any(Instant.class), any(Collection.class)))
        .thenReturn(List.of(row));

    Duration retry = service().retryAfterForUsername("alice", now);

    // Allow ±1 second slack for rounding.
    assertThat(retry.toSeconds()).isBetween(299L, 301L);
  }

  @Test
  void retryAfter_neverZero_evenWhenWindowJustElapsed() {
    Instant boundary = now.minus(Duration.ofMinutes(15));
    LoginAttempt row =
        LoginAttempt.builder()
            .id(UUID.randomUUID())
            .usernameNormalised("alice")
            .sourceIp("203.0.113.5")
            .succeeded(false)
            .failureReason(LoginFailureReason.BAD_PASSWORD)
            .attemptedAt(boundary)
            .build();
    when(repo.findByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonInOrderByAttemptedAtAsc(
            eq("alice"), any(Instant.class), any(Collection.class)))
        .thenReturn(List.of(row));

    Duration retry = service().retryAfterForUsername("alice", now);

    assertThat(retry.toSeconds()).isGreaterThanOrEqualTo(1L);
  }

  @Test
  void retryAfter_emptyResults_returnsOneSecondFloor() {
    when(repo.findByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonInOrderByAttemptedAtAsc(
            eq("alice"), any(Instant.class), any(Collection.class)))
        .thenReturn(List.of());

    Duration retry = service().retryAfterForUsername("alice", now);

    assertThat(retry).isEqualTo(Duration.ofSeconds(1));
  }

  // ---------------- exact ceiling + retryAfterForIp + window() ----------------

  private LoginAttempt failedAttempt(Instant attemptedAt) {
    return LoginAttempt.builder()
        .id(UUID.randomUUID())
        .usernameNormalised("alice")
        .sourceIp("203.0.113.5")
        .succeeded(false)
        .failureReason(LoginFailureReason.BAD_PASSWORD)
        .attemptedAt(attemptedAt)
        .build();
  }

  /**
   * Oldest attempt was 13m29.5s ago, so the release is 90.5s in the future → {@code toSeconds()}
   * truncates to 90 and the fractional remainder forces the round-up branch to {@code 90 + 1 = 91}.
   * A MULTI-second fractional remainder (not the 0.5s sub-second case) is essential: it makes the
   * {@code seconds += 1} math mutant observable as 91 vs 89 WITHOUT the {@code if (seconds < 1)}
   * floor on line 120 masking it (89 stays 89). Asserting the EXACT 91 (not a ±1 range like the
   * legacy tests) is what kills the surviving line-118 MathMutator.
   */
  @Test
  void retryAfterForUsername_multiSecondFractionalRemainder_ceilsExactlyUp() {
    Instant oldest = now.minus(Duration.ofMinutes(15)).plusSeconds(90).plusMillis(500);
    when(repo.findByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonInOrderByAttemptedAtAsc(
            eq("alice"), any(Instant.class), any(Collection.class)))
        .thenReturn(List.of(failedAttempt(oldest)));

    Duration retry = service().retryAfterForUsername("alice", now);

    assertThat(retry).isEqualTo(Duration.ofSeconds(91));
  }

  /**
   * Exactly-whole remaining duration (no fractional nanos) must NOT be incremented — pins the other
   * side of the line-117 boundary so a {@code >=} mutant is caught. Oldest 10s short of the window
   * → 10s remaining exactly.
   */
  @Test
  void retryAfterForUsername_wholeSecondRemainder_isNotIncremented() {
    Instant oldest = now.minus(Duration.ofMinutes(15)).plusSeconds(10);
    when(repo.findByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonInOrderByAttemptedAtAsc(
            eq("alice"), any(Instant.class), any(Collection.class)))
        .thenReturn(List.of(failedAttempt(oldest)));

    Duration retry = service().retryAfterForUsername("alice", now);

    assertThat(retry).isEqualTo(Duration.ofSeconds(10));
  }

  /**
   * {@code retryAfterForIp} was entirely uncovered (line 96 null-return mutant survived). Drive it
   * with one IP-scoped failed attempt and pin the exact ceiling.
   */
  @Test
  void retryAfterForIp_returnsCeilingFromOldestIpAttempt() {
    Instant oldest = now.minus(Duration.ofMinutes(15)).plusSeconds(120);
    when(repo.findBySourceIpAndAttemptedAtAfterAndFailureReasonInOrderByAttemptedAtAsc(
            eq("203.0.113.5"), any(Instant.class), any(Collection.class)))
        .thenReturn(List.of(failedAttempt(oldest)));

    Duration retry = service().retryAfterForIp("203.0.113.5", now);

    assertThat(retry).isEqualTo(Duration.ofSeconds(120));
  }

  @Test
  void retryAfterForIp_noAttempts_returnsOneSecondFloor() {
    when(repo.findBySourceIpAndAttemptedAtAfterAndFailureReasonInOrderByAttemptedAtAsc(
            eq("203.0.113.5"), any(Instant.class), any(Collection.class)))
        .thenReturn(List.of());

    assertThat(service().retryAfterForIp("203.0.113.5", now)).isEqualTo(Duration.ofSeconds(1));
  }

  /** {@code window()} accessor was uncovered (line 101 null-return mutant survived). */
  @Test
  void window_returnsConfiguredFifteenMinuteDefault() {
    assertThat(service().window()).isEqualTo(Duration.ofMinutes(15));
  }
}
