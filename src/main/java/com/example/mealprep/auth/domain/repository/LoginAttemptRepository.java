package com.example.mealprep.auth.domain.repository;

import com.example.mealprep.auth.domain.entity.LoginAttempt;
import com.example.mealprep.auth.domain.entity.LoginFailureReason;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link LoginAttempt}.
 *
 * <p>The throttle service queries this repo directly with windowed counts rather than keeping a
 * separate counter table — index {@code idx_auth_login_attempts_username_time} keeps the lookup
 * hot.
 */
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {

  long countByUsernameNormalisedAndAttemptedAtAfterAndSucceededFalse(
      String usernameNormalised, Instant since);

  long countBySourceIpAndAttemptedAtAfterAndSucceededFalse(String sourceIp, Instant since);

  /**
   * "Consecutive" = no successful login between now and {@code since}. Used to drive the lockout
   * threshold.
   */
  long countByUsernameNormalisedAndAttemptedAtAfter(String usernameNormalised, Instant since);

  /**
   * Throttle-window count for a single username, restricted to the failure reasons that actually
   * indicate brute-force pressure ({@code BAD_PASSWORD}, {@code UNKNOWN_USER}). {@code
   * ACCOUNT_LOCKED} and {@code THROTTLED} are intentionally excluded — counting them would let an
   * attacker who already tripped the throttle keep the throttle tripped indefinitely just by
   * retrying.
   */
  long countByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonIn(
      String usernameNormalised, Instant since, Collection<LoginFailureReason> reasons);

  long countBySourceIpAndAttemptedAtAfterAndFailureReasonIn(
      String sourceIp, Instant since, Collection<LoginFailureReason> reasons);

  /**
   * Returns rows in {@code reasons} for the given username after {@code since}, ordered by attempt
   * time ascending. Used to compute the {@code Retry-After} header — the oldest counted attempt's
   * {@code attemptedAt + window} is the earliest moment the throttle releases.
   */
  List<LoginAttempt>
      findByUsernameNormalisedAndAttemptedAtAfterAndFailureReasonInOrderByAttemptedAtAsc(
          String usernameNormalised, Instant since, Collection<LoginFailureReason> reasons);

  List<LoginAttempt> findBySourceIpAndAttemptedAtAfterAndFailureReasonInOrderByAttemptedAtAsc(
      String sourceIp, Instant since, Collection<LoginFailureReason> reasons);
}
