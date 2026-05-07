package com.example.mealprep.auth.domain.service.internal;

import com.example.mealprep.auth.api.dto.LoginRequest;
import com.example.mealprep.auth.api.dto.PasswordChangeRequest;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.api.dto.UserDto;
import com.example.mealprep.auth.api.mapper.UserMapper;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.entity.Session;
import com.example.mealprep.auth.domain.entity.User;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.domain.service.AuthQueryService;
import com.example.mealprep.auth.domain.service.AuthUpdateService;
import com.example.mealprep.auth.domain.service.LoginContext;
import com.example.mealprep.auth.domain.service.LoginOutcome;
import com.example.mealprep.auth.event.UserLoggedInEvent;
import com.example.mealprep.auth.event.UserPasswordChangedEvent;
import com.example.mealprep.auth.event.UserRegisteredEvent;
import com.example.mealprep.auth.exception.InvalidCredentialsException;
import com.example.mealprep.auth.exception.UsernameAlreadyExistsException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of both {@link AuthQueryService} and {@link AuthUpdateService}.
 *
 * <p>Reads run with {@code readOnly=true}; writes run REQUIRED. {@code register} catches the
 * unique-index collision and translates it into a domain exception (409) — relying on the DB
 * uniqueness check rather than an existence pre-check is the canonical "concurrent registration is
 * safe" pattern.
 */
@Service
public class AuthServiceImpl implements AuthQueryService, AuthUpdateService {

  private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

  private final UserRepository userRepository;
  private final SessionRepository sessionRepository;
  private final UserMapper userMapper;
  private final PasswordHasher passwordHasher;
  private final PasswordStrengthValidator passwordStrengthValidator;
  private final SessionTokenGenerator tokenGenerator;
  private final ApplicationEventPublisher eventPublisher;
  private final AuthProperties authProperties;
  private final Clock clock;

  public AuthServiceImpl(
      UserRepository userRepository,
      SessionRepository sessionRepository,
      UserMapper userMapper,
      PasswordHasher passwordHasher,
      PasswordStrengthValidator passwordStrengthValidator,
      SessionTokenGenerator tokenGenerator,
      ApplicationEventPublisher eventPublisher,
      AuthProperties authProperties,
      Clock clock) {
    this.userRepository = userRepository;
    this.sessionRepository = sessionRepository;
    this.userMapper = userMapper;
    this.passwordHasher = passwordHasher;
    this.passwordStrengthValidator = passwordStrengthValidator;
    this.tokenGenerator = tokenGenerator;
    this.eventPublisher = eventPublisher;
    this.authProperties = authProperties;
    this.clock = clock;
  }

  // ---------------- Query ----------------

  @Override
  @Transactional(readOnly = true)
  public Optional<UserDto> getUser(UUID userId) {
    return userRepository
        .findById(userId)
        .filter(u -> u.getDeletedAt() == null)
        .map(userMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<UserDto> getUsersByIds(Collection<UUID> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return Collections.emptyList();
    }
    return userRepository.findByIdIn(userIds).stream()
        .filter(u -> u.getDeletedAt() == null)
        .map(userMapper::toDto)
        .collect(Collectors.toUnmodifiableList());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UserDto> getUserByUsername(String username) {
    if (username == null) {
      return Optional.empty();
    }
    return userRepository
        .findByUsernameNormalisedAndDeletedAtIsNull(normalise(username))
        .map(userMapper::toDto);
  }

  // ---------------- Register ----------------

  @Override
  @Transactional
  public LoginOutcome register(RegisterRequest request, LoginContext loginContext) {
    String normalised = normalise(request.username());
    // Service-side belt-and-braces — annotation can't see the username so equality lives here.
    var reasons = passwordStrengthValidator.evaluate(request.password(), request.username());
    if (!reasons.isEmpty()) {
      throw new IllegalArgumentException("Password rejected: " + reasons);
    }

    Instant now = Instant.now(clock);
    UUID userId = UUID.randomUUID();
    User user =
        User.builder()
            .id(userId)
            .username(request.username())
            .usernameNormalised(normalised)
            .passwordHash(passwordHasher.hash(request.password()))
            .passwordUpdatedAt(now)
            .failedLoginCount(0)
            .build();

    User saved;
    try {
      saved = userRepository.saveAndFlush(user);
    } catch (DataIntegrityViolationException ex) {
      // Unique-index collision on username_normalised — turns into 409 at the controller seam.
      throw new UsernameAlreadyExistsException("Username already taken: " + request.username(), ex);
    }

    LoginOutcome outcome = issueSession(saved, loginContext, now);
    eventPublisher.publishEvent(
        new UserRegisteredEvent(saved.getId(), saved.getUsername(), now, UUID.randomUUID(), now));
    log.info("user registered userId={}", saved.getId());
    return outcome;
  }

  // ---------------- Login ----------------

  @Override
  @Transactional
  public LoginOutcome login(LoginRequest request, LoginContext loginContext) {
    String normalised = normalise(request.username());
    Optional<User> userOpt = userRepository.findByUsernameNormalisedAndDeletedAtIsNull(normalised);
    if (userOpt.isEmpty()) {
      // 01a does not run dummy verify; timing-parity is the 01b job. Generic exception either way.
      throw new InvalidCredentialsException();
    }
    User user = userOpt.get();
    if (!passwordHasher.verify(request.password(), user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }

    Instant now = Instant.now(clock);
    user.setFailedLoginCount(0);
    user.setLastLoginAt(now);
    user.setLastLoginIp(loginContext == null ? null : loginContext.sourceIp());
    User saved = userRepository.save(user);

    LoginOutcome outcome = issueSession(saved, loginContext, now);
    eventPublisher.publishEvent(
        new UserLoggedInEvent(
            saved.getId(),
            outcome.sessionId(),
            loginContext == null ? null : loginContext.sourceIp(),
            loginContext == null ? null : loginContext.userAgent(),
            now,
            UUID.randomUUID(),
            now));
    log.info("user logged in userId={} sessionId={}", saved.getId(), outcome.sessionId());
    return outcome;
  }

  // ---------------- Logout ----------------

  @Override
  @Transactional
  public void logout(UUID sessionId) {
    if (sessionId == null) {
      // Defensive — controller should resolve a session before calling, but logout is idempotent.
      return;
    }
    Optional<Session> sessionOpt = sessionRepository.findById(sessionId);
    if (sessionOpt.isEmpty()) {
      return;
    }
    Session session = sessionOpt.get();
    if (session.getRevokedAt() != null) {
      return;
    }
    session.setRevokedAt(Instant.now(clock));
    sessionRepository.save(session);
    log.info("session revoked sessionId={}", sessionId);
  }

  // ---------------- Password change ----------------

  @Override
  @Transactional
  public LoginOutcome changePassword(
      UUID currentSessionId, PasswordChangeRequest request, LoginContext loginContext) {
    if (currentSessionId == null) {
      // Belt-and-braces — controller should reject anonymous before the service is called.
      throw new InvalidCredentialsException();
    }
    Session callingSession =
        sessionRepository.findById(currentSessionId).orElseThrow(InvalidCredentialsException::new);
    if (callingSession.getRevokedAt() != null) {
      throw new InvalidCredentialsException();
    }
    User user =
        userRepository
            .findById(callingSession.getUserId())
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(InvalidCredentialsException::new);

    // Step 1 — verify the current password before doing anything else. A wrong currentPassword
    // must surface as 401 with the generic "Invalid credentials", not as a 400 about new-password
    // shape (no enumeration / probing oracle).
    if (!passwordHasher.verify(request.currentPassword(), user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }

    // Step 2 — service-side strength validation (username equality — the annotation can't see the
    // username). Bean validation already enforced length / whitespace at controller bind.
    var reasons = passwordStrengthValidator.evaluate(request.newPassword(), user.getUsername());
    if (!reasons.isEmpty()) {
      throw new IllegalArgumentException("Password rejected: " + reasons);
    }

    Instant now = Instant.now(clock);

    // Step 3 — update password fields on the user row. @Version on User catches concurrent
    // password changes from a different session and blows up the second tx with an
    // OptimisticLockException, which the global handler maps to 409.
    user.setPasswordHash(passwordHasher.hash(request.newPassword()));
    user.setPasswordUpdatedAt(now);
    userRepository.save(user);

    // Step 4 — bulk-revoke every OTHER active session for this user. The calling session is
    // excluded here and re-issued separately so the user isn't bounced.
    int sessionsRevokedCount =
        sessionRepository.revokeAllActiveForUserExcept(user.getId(), callingSession.getId(), now);

    // Step 5 — revoke the calling session row itself, then issue a fresh session row with a fresh
    // token. The old cookie value is now unusable; the controller writes the new token to a
    // Set-Cookie header.
    callingSession.setRevokedAt(now);
    sessionRepository.save(callingSession);
    LoginOutcome outcome = issueSession(user, loginContext, now);

    // Step 6 — publish AFTER_COMMIT (Spring publishes via ApplicationEventPublisher; the event
    // listener decides whether to bind to AFTER_COMMIT — this code path emits unconditionally).
    eventPublisher.publishEvent(
        new UserPasswordChangedEvent(user.getId(), sessionsRevokedCount, UUID.randomUUID(), now));
    log.info(
        "user changed password userId={} oldSessionId={} newSessionId={} otherSessionsRevoked={}",
        user.getId(),
        callingSession.getId(),
        outcome.sessionId(),
        sessionsRevokedCount);
    return outcome;
  }

  // ---------------- Helpers ----------------

  private LoginOutcome issueSession(User user, LoginContext loginContext, Instant now) {
    String rawToken = tokenGenerator.generateRawToken();
    String hash = tokenGenerator.hash(rawToken);
    Instant expiresAt = now.plus(authProperties.sessionTtl());
    Session session =
        Session.builder()
            .id(UUID.randomUUID())
            .userId(user.getId())
            .tokenHash(hash)
            .issuedAt(now)
            .expiresAt(expiresAt)
            .lastSeenAt(now)
            .issuingIp(loginContext == null ? null : loginContext.sourceIp())
            .userAgent(loginContext == null ? null : loginContext.userAgent())
            .build();
    Session persisted = sessionRepository.save(session);
    return new LoginOutcome(userMapper.toDto(user), persisted.getId(), expiresAt, rawToken);
  }

  private static String normalise(String username) {
    return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
  }
}
