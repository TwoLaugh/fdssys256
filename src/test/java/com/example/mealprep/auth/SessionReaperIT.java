package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.entity.Session;
import com.example.mealprep.auth.domain.entity.User;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.domain.service.internal.SessionReaper;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * IT for the {@link SessionReaper} (LLD Flow 6, auth-3). Seeds sessions in four states relative to
 * the {@code retain-revoked-for} (default 7d) cutoff and drives {@link SessionReaper#sweep()}
 * directly (the {@code @Scheduled} trigger is disabled in the test profile via a never-matching
 * cron). Asserts only the stale rows — past their absolute expiry, or revoked before the cutoff —
 * are hard-deleted, and rows still inside the retention window survive.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class SessionReaperIT {

  @Autowired private SessionReaper sessionReaper;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private Session session(UUID userId, Instant expiresAt, Instant revokedAt) {
    Instant issued = Instant.now().minus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
    return Session.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .tokenHash(UUID.randomUUID().toString().replace("-", ""))
        .issuedAt(issued)
        .expiresAt(expiresAt)
        .lastSeenAt(issued)
        .revokedAt(revokedAt)
        .build();
  }

  @Test
  void sweep_deletesExpiredAndLongRevokedSessions_keepsActiveAndRecentlyRevoked() {
    User user = userRepository.saveAndFlush(AuthTestData.user().build());
    UUID uid = user.getId();
    Instant now = Instant.now();
    Duration retain = authProperties.session().retainRevokedFor(); // 7d default

    // (a) expired well before the cutoff (now - retain) → DELETE.
    Session expiredLongAgo = session(uid, now.minus(retain).minus(Duration.ofDays(3)), null);
    // (b) revoked well before the cutoff (expiry still in the future) → DELETE.
    Session revokedLongAgo =
        session(uid, now.plus(30, ChronoUnit.DAYS), now.minus(retain).minus(Duration.ofDays(3)));
    // (c) revoked recently (inside the retention window) → KEEP.
    Session revokedRecently =
        session(uid, now.plus(30, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
    // (d) active, unexpired, not revoked → KEEP.
    Session active = session(uid, now.plus(30, ChronoUnit.DAYS), null);

    sessionRepository.saveAll(
        java.util.List.of(expiredLongAgo, revokedLongAgo, revokedRecently, active));
    sessionRepository.flush();

    int deleted = sessionReaper.sweep();

    assertThat(deleted).isEqualTo(2);
    Set<UUID> remaining =
        sessionRepository.findAll().stream().map(Session::getId).collect(Collectors.toSet());
    assertThat(remaining)
        .containsExactlyInAnyOrder(revokedRecently.getId(), active.getId())
        .doesNotContain(expiredLongAgo.getId(), revokedLongAgo.getId());
  }

  @Test
  void sweep_isNoOp_whenNothingIsStale() {
    User user = userRepository.saveAndFlush(AuthTestData.user().build());
    Instant now = Instant.now();
    sessionRepository.saveAndFlush(session(user.getId(), now.plus(30, ChronoUnit.DAYS), null));

    assertThat(sessionReaper.sweep()).isZero();
    assertThat(sessionRepository.count()).isEqualTo(1L);
  }

  @Test
  void runScheduled_delegatesToSweep_andReturnsDeletedCount() {
    User user = userRepository.saveAndFlush(AuthTestData.user().build());
    Instant now = Instant.now();
    Duration retain = authProperties.session().retainRevokedFor();
    sessionRepository.saveAndFlush(
        session(user.getId(), now.minus(retain).minus(Duration.ofDays(2)), null));

    assertThat(sessionReaper.runScheduled()).isEqualTo(1);
    assertThat(sessionRepository.count()).isZero();
  }
}
