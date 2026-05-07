package com.example.mealprep.auth.domain.service.internal;

import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.entity.LoginAttempt;
import com.example.mealprep.auth.domain.entity.LoginFailureReason;
import com.example.mealprep.auth.domain.repository.LoginAttemptRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Windowed-count throttle for login attempts. Counts only the failure reasons that indicate
 * brute-force pressure ({@code BAD_PASSWORD}, {@code UNKNOWN_USER}); {@code ACCOUNT_LOCKED} and
 * {@code THROTTLED} are excluded so an already-throttled caller cannot keep the throttle armed by
 * retrying.
 *
 * <p>The two checks are independent: per-username defends against single-account brute force,
 * per-IP defends against credential-stuffing across accounts. The login flow runs both before any
 * DB lookup of the user — see {@link AuthServiceImpl#login} — so the throttle decision never leaks
 * username existence via timing.
 */
@Component
public class LoginThrottleService {

  /**
   * Failure reasons that count toward the throttle. {@code ACCOUNT_LOCKED} / {@code THROTTLED} are
   * intentionally excluded.
   */
  static final Set<LoginFailureReason> COUNTED_REASONS =
      EnumSet.of(LoginFailureReason.BAD_PASSWORD, LoginFailureReason.UNKNOWN_USER);

  private final LoginAttemptRepository loginAttemptRepository;
  private final Duration window;
  private final int usernameMaxFailures;
  private final int ipMaxFailures;

  public LoginThrottleService(
      LoginAttemptRepository loginAttemptRepository, AuthProperties authProperties) {
    this.loginAttemptRepository = loginAttemptRepository;
    this.window = authProperties.throttle().window();
    this.usernameMaxFailures = authProperties.throttle().usernameMaxFailures();
    this.ipMaxFailures = authProperties.throttle().ipMaxFailures();
  }

  /**
   * @return true if the per-username failure count in the rolling window has reached the configured
   *     cap. Caller should throw {@code LoginThrottledException}.
   */
  public boolean shouldThrottleByUsername(String usernameNormalised, Instant now) {
    if (usernameNormalised == null || usernameNormalised.isEmpty()) {
      return false;
    }
    Instant since = now.minus(window);
    long count =
        loginAttemptRepository.countByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonIn(
            usernameNormalised, since, COUNTED_REASONS);
    return count >= usernameMaxFailures;
  }

  /**
   * @return true if the per-IP failure count in the rolling window has reached the configured cap.
   */
  public boolean shouldThrottleByIp(String sourceIp, Instant now) {
    if (sourceIp == null || sourceIp.isEmpty()) {
      return false;
    }
    Instant since = now.minus(window);
    long count =
        loginAttemptRepository.countBySourceIpAndAttemptedAtAfterAndFailureReasonIn(
            sourceIp, since, COUNTED_REASONS);
    return count >= ipMaxFailures;
  }

  /**
   * Compute the {@code Retry-After} duration for a username throttle hit. Rounded up to whole
   * seconds, never less than one — clients should not be told to retry "in zero seconds".
   */
  public Duration retryAfterForUsername(String usernameNormalised, Instant now) {
    Instant since = now.minus(window);
    List<LoginAttempt> rows =
        loginAttemptRepository
            .findByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonInOrderByAttemptedAtAsc(
                usernameNormalised, since, COUNTED_REASONS);
    return retryAfterFromOldest(rows, now);
  }

  public Duration retryAfterForIp(String sourceIp, Instant now) {
    Instant since = now.minus(window);
    List<LoginAttempt> rows =
        loginAttemptRepository
            .findBySourceIpAndAttemptedAtAfterAndFailureReasonInOrderByAttemptedAtAsc(
                sourceIp, since, COUNTED_REASONS);
    return retryAfterFromOldest(rows, now);
  }

  /** Exposes the configured window for callers that need it (tests, audit logging). */
  public Duration window() {
    return window;
  }

  private Duration retryAfterFromOldest(List<LoginAttempt> rows, Instant now) {
    if (rows == null || rows.isEmpty()) {
      // Window will start emptying immediately; floor to one second so we never advise zero.
      return Duration.ofSeconds(1);
    }
    Instant oldest = rows.get(0).getAttemptedAt();
    Instant releaseAt = oldest.plus(window);
    Duration remaining = Duration.between(now, releaseAt);
    if (remaining.isNegative() || remaining.isZero()) {
      return Duration.ofSeconds(1);
    }
    // Round up to whole seconds.
    long seconds = remaining.toSeconds();
    if (remaining.minusSeconds(seconds).toNanos() > 0) {
      seconds += 1;
    }
    if (seconds < 1) {
      seconds = 1;
    }
    return Duration.ofSeconds(seconds);
  }
}
