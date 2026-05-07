package com.example.mealprep.auth.domain.repository;

import com.example.mealprep.auth.domain.entity.LoginAttempt;
import java.time.Instant;
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
}
