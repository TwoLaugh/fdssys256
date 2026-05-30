package com.example.mealprep.auth.domain.service.internal;

import com.example.mealprep.auth.domain.repository.SessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Best-effort single-session revoke used by {@code SessionAuthenticationFilter} when it observes a
 * still-active session belonging to a soft-deleted user (auth-6, LLD Flow 3 step 3).
 *
 * <p>It is a <b>separate bean</b> (not a method on the filter) so {@code REQUIRES_NEW} is applied
 * by the Spring proxy — a self-invoked {@code @Transactional} method would silently bypass the
 * proxy (the exact trap auth-8 flags elsewhere). The filter invokes {@link #revoke(UUID)} through
 * the proxy, so the {@code @Modifying} write gets its own transaction; the filter's own catch-all
 * keeps any failure here from propagating out of the request (it just proceeds anonymous).
 */
@Component
public class SessionRevoker {

  private static final Logger log = LoggerFactory.getLogger(SessionRevoker.class);

  private final SessionRepository sessionRepository;
  private final Clock clock;

  public SessionRevoker(SessionRepository sessionRepository, Clock clock) {
    this.sessionRepository = sessionRepository;
    this.clock = clock;
  }

  /**
   * Revoke the given session in its own ({@code REQUIRES_NEW}) transaction. Invoked through the
   * Spring proxy from the filter so the transaction actually applies; the filter wraps the call so
   * a failure never affects the inbound authentication decision.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void revoke(UUID sessionId) {
    int revoked = sessionRepository.revokeById(sessionId, Instant.now(clock));
    if (revoked > 0) {
      log.info("revoked session of soft-deleted user sessionId={}", sessionId);
    }
  }
}
