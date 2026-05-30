package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.api.dto.LoginRequest;
import com.example.mealprep.auth.api.dto.PasswordChangeRequest;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.api.dto.UserDto;
import com.example.mealprep.auth.api.mapper.UserMapper;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.entity.LoginAttempt;
import com.example.mealprep.auth.domain.entity.LoginFailureReason;
import com.example.mealprep.auth.domain.entity.Session;
import com.example.mealprep.auth.domain.entity.User;
import com.example.mealprep.auth.domain.repository.LoginAttemptRepository;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.domain.service.LoginContext;
import com.example.mealprep.auth.domain.service.LoginOutcome;
import com.example.mealprep.auth.domain.service.internal.AuthServiceImpl;
import com.example.mealprep.auth.domain.service.internal.LoginThrottleService;
import com.example.mealprep.auth.domain.service.internal.PasswordHasher;
import com.example.mealprep.auth.domain.service.internal.PasswordStrengthValidator;
import com.example.mealprep.auth.domain.service.internal.SessionTokenGenerator;
import com.example.mealprep.auth.event.UserLoggedInEvent;
import com.example.mealprep.auth.event.UserPasswordChangedEvent;
import com.example.mealprep.auth.event.UserRegisteredEvent;
import com.example.mealprep.auth.exception.AccountLockedException;
import com.example.mealprep.auth.exception.InvalidCredentialsException;
import com.example.mealprep.auth.exception.LoginThrottledException;
import com.example.mealprep.auth.exception.UsernameAlreadyExistsException;
import com.example.mealprep.auth.exception.WeakPasswordException;
import com.example.mealprep.auth.testdata.AuthTestData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit test for {@link AuthServiceImpl}. {@link PasswordHasher} and {@link SessionTokenGenerator}
 * are real (cheap, no I/O); repositories, throttle service, and event publisher are mocked because
 * they are at the module boundary.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

  @Mock private UserRepository userRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private LoginAttemptRepository loginAttemptRepository;
  @Mock private LoginThrottleService loginThrottleService;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final AuthProperties properties =
      new AuthProperties(12, null, null, null, null, null, null, null, null, null, null);
  private final PasswordHasher passwordHasher = new PasswordHasher(properties);
  private final PasswordStrengthValidator strengthValidator =
      new PasswordStrengthValidator(properties);
  private final SessionTokenGenerator tokenGenerator = new SessionTokenGenerator();
  private final UserMapper userMapper = new com.example.mealprep.auth.api.mapper.UserMapperImpl();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-07T12:00:00Z"), ZoneOffset.UTC);

  private AuthServiceImpl service() {
    return new AuthServiceImpl(
        userRepository,
        sessionRepository,
        loginAttemptRepository,
        userMapper,
        passwordHasher,
        strengthValidator,
        tokenGenerator,
        loginThrottleService,
        eventPublisher,
        properties,
        fixedClock);
  }

  // ---------------- register ----------------

  @Test
  void register_persistsUserAndSession_andPublishesEvent() {
    AuthServiceImpl service = service();
    RegisterRequest request = new RegisterRequest("Alice", "correct-horse-battery");
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

    LoginOutcome outcome =
        service.register(request, new LoginContext("203.0.113.5", "Mozilla/5.0"));

    ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
    verify(userRepository).saveAndFlush(savedUser.capture());
    User user = savedUser.getValue();
    assertThat(user.getUsername()).isEqualTo("Alice");
    assertThat(user.getUsernameNormalised()).isEqualTo("alice");
    assertThat(user.getPasswordHash()).isNotEqualTo("correct-horse-battery").startsWith("$2");

    ArgumentCaptor<Session> savedSession = ArgumentCaptor.forClass(Session.class);
    verify(sessionRepository).save(savedSession.capture());
    Session session = savedSession.getValue();
    assertThat(session.getUserId()).isEqualTo(user.getId());
    assertThat(session.getTokenHash()).hasSize(64);
    assertThat(session.getTokenHash()).isNotEqualTo(outcome.rawSessionToken());
    assertThat(session.getExpiresAt()).isEqualTo(Instant.parse("2026-06-06T12:00:00Z"));

    assertThat(outcome.user().username()).isEqualTo("Alice");
    assertThat(outcome.rawSessionToken()).isNotBlank();

    ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue()).isInstanceOf(UserRegisteredEvent.class);
    UserRegisteredEvent published = (UserRegisteredEvent) event.getValue();
    assertThat(published.userId()).isEqualTo(user.getId());
    assertThat(published.username()).isEqualTo("Alice");

    // Register does NOT write a LoginAttempt row — registration is not a login.
    verify(loginAttemptRepository, never()).save(any());
  }

  @Test
  void register_translatesUniqueViolationInto409Exception() {
    AuthServiceImpl service = service();
    when(userRepository.saveAndFlush(any(User.class)))
        .thenThrow(new DataIntegrityViolationException("dup"));

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterRequest("Alice", "correct-horse-battery"),
                    new LoginContext("ip", "ua")))
        .isInstanceOf(UsernameAlreadyExistsException.class)
        .hasMessageContaining("Alice");

    verify(eventPublisher, never()).publishEvent(any());
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void register_rejectsPasswordEqualToUsername() {
    AuthServiceImpl service = service();

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterRequest("alice-batteryyy", "Alice-Batteryyy"),
                    new LoginContext("ip", "ua")))
        .isInstanceOf(WeakPasswordException.class)
        // Reason codes live on the exception, NOT in the message (auth-5: no policy leak).
        .satisfies(
            ex ->
                assertThat(((WeakPasswordException) ex).reasons())
                    .contains(PasswordStrengthValidator.Reason.MATCHES_USERNAME));

    verify(userRepository, never()).saveAndFlush(any());
  }

  // ---------------- login ----------------

  @Test
  void login_succeeds_andResetsFailedCount_andSetsLastLogin_andClearsLockout() {
    AuthServiceImpl service = service();
    User user =
        AuthTestData.user()
            .withUsername("alice")
            .withPasswordHash(passwordHasher.hash("correct-horse-battery"))
            .build();
    user.setFailedLoginCount(3);
    user.setLockedUntil(null); // not locked but had prior failures
    when(userRepository.findByUsernameNormalisedAndDeletedAtIsNull("alice"))
        .thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

    LoginOutcome outcome =
        service.login(
            new LoginRequest("alice", "correct-horse-battery"),
            new LoginContext("203.0.113.5", "ua"));

    assertThat(user.getFailedLoginCount()).isZero();
    assertThat(user.getLockedUntil()).isNull();
    assertThat(user.getLastLoginAt()).isEqualTo(Instant.parse("2026-05-07T12:00:00Z"));
    assertThat(user.getLastLoginIp()).isEqualTo("203.0.113.5");
    assertThat(outcome.rawSessionToken()).isNotBlank();
    assertThat(outcome.user().userId()).isEqualTo(user.getId());

    ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue()).isInstanceOf(UserLoggedInEvent.class);
    UserLoggedInEvent published = (UserLoggedInEvent) event.getValue();
    assertThat(published.userId()).isEqualTo(user.getId());
    assertThat(published.ipAddress()).isEqualTo("203.0.113.5");

    // Audit row written, succeeded=true, no failureReason.
    ArgumentCaptor<LoginAttempt> attempt = ArgumentCaptor.forClass(LoginAttempt.class);
    verify(loginAttemptRepository).save(attempt.capture());
    assertThat(attempt.getValue().isSucceeded()).isTrue();
    assertThat(attempt.getValue().getFailureReason()).isNull();
    assertThat(attempt.getValue().getUserId()).isEqualTo(user.getId());
    assertThat(attempt.getValue().getSourceIp()).isEqualTo("203.0.113.5");
  }

  @Test
  void login_throwsInvalidCredentials_andRunsDummyVerify_andWritesUnknownUserAudit() {
    AuthServiceImpl service = service();
    when(userRepository.findByUsernameNormalisedAndDeletedAtIsNull("alice"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.login(
                    new LoginRequest("alice", "correct-horse-battery"),
                    new LoginContext("203.0.113.5", "ua")))
        .isInstanceOf(InvalidCredentialsException.class)
        .hasMessage("Invalid credentials");

    verify(sessionRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());

    ArgumentCaptor<LoginAttempt> attempt = ArgumentCaptor.forClass(LoginAttempt.class);
    verify(loginAttemptRepository).save(attempt.capture());
    assertThat(attempt.getValue().isSucceeded()).isFalse();
    assertThat(attempt.getValue().getFailureReason()).isEqualTo(LoginFailureReason.UNKNOWN_USER);
    assertThat(attempt.getValue().getUserId()).isNull();
    assertThat(attempt.getValue().getUsernameNormalised()).isEqualTo("alice");
  }

  @Test
  void login_dummyHashIsValidBcryptFormat() {
    // The hardcoded dummy hash must parse — otherwise the unknown-user path short-circuits and the
    // timing-parity guarantee dissolves. We exercise the same encoder used at runtime.
    boolean matches = passwordHasher.verify("anything", AuthServiceImpl.DUMMY_BCRYPT_HASH);
    assertThat(matches).isFalse();
    assertThat(AuthServiceImpl.DUMMY_BCRYPT_HASH).startsWith("$2").hasSize(60);
  }

  @Test
  void login_throwsInvalidCredentials_forWrongPassword_withSameMessageAsUnknownUser() {
    AuthServiceImpl service = service();
    User user =
        AuthTestData.user()
            .withUsername("alice")
            .withPasswordHash(passwordHasher.hash("correct-horse-battery"))
            .build();
    when(userRepository.findByUsernameNormalisedAndDeletedAtIsNull("alice"))
        .thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    assertThatThrownBy(
            () ->
                service.login(
                    new LoginRequest("alice", "the-wrong-password"), new LoginContext("ip", "ua")))
        .isInstanceOf(InvalidCredentialsException.class)
        .hasMessage("Invalid credentials");

    verify(sessionRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());

    // failedLoginCount incremented and BAD_PASSWORD audit row written.
    assertThat(user.getFailedLoginCount()).isEqualTo(1);
    ArgumentCaptor<LoginAttempt> attempt = ArgumentCaptor.forClass(LoginAttempt.class);
    verify(loginAttemptRepository).save(attempt.capture());
    assertThat(attempt.getValue().getFailureReason()).isEqualTo(LoginFailureReason.BAD_PASSWORD);
    assertThat(attempt.getValue().getUserId()).isEqualTo(user.getId());
  }

  @Test
  void login_locksUser_onThresholdReached() {
    AuthServiceImpl service = service();
    User user =
        AuthTestData.user()
            .withUsername("alice")
            .withPasswordHash(passwordHasher.hash("correct-horse-battery"))
            .build();
    user.setFailedLoginCount(4); // one shy of threshold (5)
    when(userRepository.findByUsernameNormalisedAndDeletedAtIsNull("alice"))
        .thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    assertThatThrownBy(
            () ->
                service.login(
                    new LoginRequest("alice", "the-wrong-password"),
                    new LoginContext("203.0.113.5", "ua")))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(user.getFailedLoginCount()).isEqualTo(5);
    assertThat(user.getLockedUntil())
        .isEqualTo(Instant.parse("2026-05-07T12:00:00Z").plus(Duration.ofMinutes(15)));
  }

  @Test
  void login_throwsAccountLocked_whenLockedUntilInFuture_andWritesAccountLockedAudit() {
    AuthServiceImpl service = service();
    Instant lockedUntil = Instant.parse("2026-05-07T12:00:00Z").plus(Duration.ofMinutes(10));
    User user =
        AuthTestData.user()
            .withUsername("alice")
            .withPasswordHash(passwordHasher.hash("correct-horse-battery"))
            .build();
    user.setLockedUntil(lockedUntil);
    when(userRepository.findByUsernameNormalisedAndDeletedAtIsNull("alice"))
        .thenReturn(Optional.of(user));

    assertThatThrownBy(
            () ->
                service.login(
                    new LoginRequest("alice", "correct-horse-battery"),
                    new LoginContext("203.0.113.5", "ua")))
        .isInstanceOf(AccountLockedException.class)
        .matches(ex -> ((AccountLockedException) ex).lockedUntil().equals(lockedUntil));

    verify(userRepository, never()).save(any());
    verify(sessionRepository, never()).save(any());

    ArgumentCaptor<LoginAttempt> attempt = ArgumentCaptor.forClass(LoginAttempt.class);
    verify(loginAttemptRepository).save(attempt.capture());
    assertThat(attempt.getValue().getFailureReason()).isEqualTo(LoginFailureReason.ACCOUNT_LOCKED);
  }

  @Test
  void login_throwsLoginThrottled_whenUsernameThrottled_andDoesNotLookupUser() {
    AuthServiceImpl service = service();
    when(loginThrottleService.shouldThrottleByUsername(any(), any())).thenReturn(true);
    when(loginThrottleService.retryAfterForUsername(any(), any()))
        .thenReturn(Duration.ofSeconds(120));

    assertThatThrownBy(
            () ->
                service.login(
                    new LoginRequest("alice", "correct-horse-battery"),
                    new LoginContext("203.0.113.5", "ua")))
        .isInstanceOf(LoginThrottledException.class)
        .matches(ex -> ((LoginThrottledException) ex).retryAfter().equals(Duration.ofSeconds(120)));

    // Critical security invariant: throttle short-circuits BEFORE the user lookup.
    verify(userRepository, never()).findByUsernameNormalisedAndDeletedAtIsNull(any());
    verify(loginAttemptRepository, times(1)).save(any());
  }

  @Test
  void login_throwsLoginThrottled_whenIpThrottled_andDoesNotLookupUser() {
    AuthServiceImpl service = service();
    when(loginThrottleService.shouldThrottleByUsername(any(), any())).thenReturn(false);
    when(loginThrottleService.shouldThrottleByIp(any(), any())).thenReturn(true);
    when(loginThrottleService.retryAfterForIp(any(), any())).thenReturn(Duration.ofSeconds(60));

    assertThatThrownBy(
            () ->
                service.login(
                    new LoginRequest("alice", "correct-horse-battery"),
                    new LoginContext("203.0.113.5", "ua")))
        .isInstanceOf(LoginThrottledException.class);

    verify(userRepository, never()).findByUsernameNormalisedAndDeletedAtIsNull(any());
  }

  @Test
  void login_unknownUserDoesNotIncrementAnyCounter() {
    // Lockout counts BAD_PASSWORD only — never UNKNOWN_USER. Otherwise an attacker could lock out
    // a real user by cycling random usernames. Since the user does not exist, there's no row to
    // update, so this is verified by the absence of any userRepository.save call.
    AuthServiceImpl service = service();
    when(userRepository.findByUsernameNormalisedAndDeletedAtIsNull("ghost"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.login(
                    new LoginRequest("ghost", "correct-horse-battery"),
                    new LoginContext("203.0.113.5", "ua")))
        .isInstanceOf(InvalidCredentialsException.class);

    verify(userRepository, never()).save(any());
  }

  // ---------------- logout ----------------

  @Test
  void logout_setsRevokedAt_andSavesSession() {
    AuthServiceImpl service = service();
    UUID sessionId = UUID.randomUUID();
    Session session = AuthTestData.session().withId(sessionId).build();
    when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

    service.logout(sessionId);

    assertThat(session.getRevokedAt()).isEqualTo(Instant.parse("2026-05-07T12:00:00Z"));
    verify(sessionRepository).save(session);
  }

  @Test
  void logout_isIdempotent_forAlreadyRevokedSession() {
    AuthServiceImpl service = service();
    UUID sessionId = UUID.randomUUID();
    Session session = AuthTestData.session().withId(sessionId).revoked().build();
    Instant originalRevoked = session.getRevokedAt();
    when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

    service.logout(sessionId);

    assertThat(session.getRevokedAt()).isEqualTo(originalRevoked);
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void logout_isNoOp_whenSessionNotFound() {
    AuthServiceImpl service = service();
    UUID sessionId = UUID.randomUUID();
    when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

    service.logout(sessionId);

    verify(sessionRepository, never()).save(any());
  }

  @Test
  void logout_isNoOp_forNullSessionId() {
    AuthServiceImpl service = service();

    service.logout(null);

    verify(sessionRepository, never()).findById(any());
  }

  // ---------------- changePassword ----------------

  @Test
  void changePassword_throwsInvalidCredentials_forWrongCurrentPassword() {
    AuthServiceImpl service = service();
    UUID sessionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    User user =
        AuthTestData.user()
            .withId(userId)
            .withUsername("alice")
            .withPasswordHash(passwordHasher.hash("the-real-current-password"))
            .build();
    Session session = AuthTestData.session().withId(sessionId).withUserId(userId).build();
    when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    assertThatThrownBy(
            () ->
                service.changePassword(
                    sessionId,
                    new PasswordChangeRequest("wrong-current-password", "fresh-new-password-12345"),
                    new LoginContext("203.0.113.5", "ua")))
        .isInstanceOf(InvalidCredentialsException.class)
        .hasMessage("Invalid credentials");

    // No user/session mutations on wrong-current.
    verify(userRepository, never()).save(any());
    verify(sessionRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());

    // BUT a BAD_PASSWORD LoginAttempt IS recorded so PUT /password shares the login throttle
    // surface (auth-2). The username is normalised and the source IP carried.
    ArgumentCaptor<LoginAttempt> attempt = ArgumentCaptor.forClass(LoginAttempt.class);
    verify(loginAttemptRepository).save(attempt.capture());
    assertThat(attempt.getValue().getFailureReason()).isEqualTo(LoginFailureReason.BAD_PASSWORD);
    assertThat(attempt.getValue().isSucceeded()).isFalse();
    assertThat(attempt.getValue().getUsernameNormalised()).isEqualTo("alice");
    assertThat(attempt.getValue().getUserId()).isEqualTo(userId);
    assertThat(attempt.getValue().getSourceIp()).isEqualTo("203.0.113.5");
  }

  @Test
  void changePassword_rejectsNewPasswordEqualToUsername() {
    AuthServiceImpl service = service();
    UUID sessionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    User user =
        AuthTestData.user()
            .withId(userId)
            .withUsername("alice-equality")
            .withPasswordHash(passwordHasher.hash("the-real-current-password"))
            .build();
    Session session = AuthTestData.session().withId(sessionId).withUserId(userId).build();
    when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    assertThatThrownBy(
            () ->
                service.changePassword(
                    sessionId,
                    new PasswordChangeRequest("the-real-current-password", "Alice-Equality"),
                    new LoginContext("ip", "ua")))
        .isInstanceOf(WeakPasswordException.class)
        // Reason codes live on the exception, NOT in the message (no block-list/policy leak).
        .satisfies(
            ex ->
                assertThat(((WeakPasswordException) ex).reasons())
                    .contains(PasswordStrengthValidator.Reason.MATCHES_USERNAME));

    // Strength failure happens AFTER currentPassword verify but BEFORE any state mutation. The
    // current password was correct, so no BAD_PASSWORD attempt is recorded here.
    verify(userRepository, never()).save(any());
    verify(sessionRepository, never()).save(any());
    verify(loginAttemptRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void changePassword_happyPath_revokesOthers_reissuesCallingSession_publishesEvent() {
    AuthServiceImpl service = service();
    UUID sessionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    String oldRawHash = passwordHasher.hash("the-real-current-password");
    User user =
        AuthTestData.user()
            .withId(userId)
            .withUsername("alice-happy")
            .withPasswordHash(oldRawHash)
            .build();
    Session callingSession = AuthTestData.session().withId(sessionId).withUserId(userId).build();
    when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(callingSession));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepository.revokeAllActiveForUserExcept(
            userId, sessionId, Instant.parse("2026-05-07T12:00:00Z")))
        .thenReturn(3);

    LoginOutcome outcome =
        service.changePassword(
            sessionId,
            new PasswordChangeRequest("the-real-current-password", "fresh-new-password-12345"),
            new LoginContext("203.0.113.5", "ua"));

    // 1. Password fields updated.
    assertThat(user.getPasswordHash()).isNotEqualTo(oldRawHash).startsWith("$2");
    assertThat(passwordHasher.verify("fresh-new-password-12345", user.getPasswordHash())).isTrue();
    assertThat(user.getPasswordUpdatedAt()).isEqualTo(Instant.parse("2026-05-07T12:00:00Z"));

    // 2. Bulk revoke went out for OTHER sessions only (calling session excluded).
    verify(sessionRepository)
        .revokeAllActiveForUserExcept(userId, sessionId, Instant.parse("2026-05-07T12:00:00Z"));

    // 3. Calling session revoked — old token is unusable.
    assertThat(callingSession.getRevokedAt()).isEqualTo(Instant.parse("2026-05-07T12:00:00Z"));

    // 4. New session row issued.
    ArgumentCaptor<Session> savedSessions = ArgumentCaptor.forClass(Session.class);
    verify(sessionRepository, org.mockito.Mockito.times(2)).save(savedSessions.capture());
    Session newSession = savedSessions.getAllValues().get(1);
    assertThat(newSession.getId()).isNotEqualTo(sessionId);
    assertThat(newSession.getUserId()).isEqualTo(userId);
    assertThat(newSession.getTokenHash()).hasSize(64);
    assertThat(newSession.getRevokedAt()).isNull();
    assertThat(outcome.sessionId()).isEqualTo(newSession.getId());
    assertThat(outcome.rawSessionToken()).isNotBlank();

    // 5. Event carries the count of OTHER sessions revoked.
    ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue()).isInstanceOf(UserPasswordChangedEvent.class);
    UserPasswordChangedEvent published = (UserPasswordChangedEvent) event.getValue();
    assertThat(published.userId()).isEqualTo(userId);
    assertThat(published.sessionsRevokedCount()).isEqualTo(3);
  }

  @Test
  void changePassword_throwsInvalidCredentials_whenSessionUnknown() {
    AuthServiceImpl service = service();
    UUID sessionId = UUID.randomUUID();
    when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.changePassword(
                    sessionId,
                    new PasswordChangeRequest("any-current", "fresh-new-password-12345"),
                    new LoginContext("ip", "ua")))
        .isInstanceOf(InvalidCredentialsException.class);

    verify(userRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  // ---------------- query ----------------

  @Test
  void getUser_filtersOutSoftDeletedUsers() {
    AuthServiceImpl service = service();
    UUID userId = UUID.randomUUID();
    User deleted = AuthTestData.user().withId(userId).softDeleted().build();
    when(userRepository.findById(userId)).thenReturn(Optional.of(deleted));

    Optional<UserDto> result = service.getUser(userId);

    assertThat(result).isEmpty();
  }

  @Test
  void getUser_returnsDtoForActiveUser() {
    AuthServiceImpl service = service();
    UUID userId = UUID.randomUUID();
    User user = AuthTestData.user().withId(userId).withUsername("alice").build();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    Optional<UserDto> result = service.getUser(userId);

    assertThat(result).isPresent();
    assertThat(result.get().username()).isEqualTo("alice");
    assertThat(result.get().userId()).isEqualTo(userId);
  }

  @Test
  void getUserByUsername_normalisesAndQueriesActiveOnly() {
    AuthServiceImpl service = service();
    User user = AuthTestData.user().withUsername("alice").build();
    when(userRepository.findByUsernameNormalisedAndDeletedAtIsNull("alice"))
        .thenReturn(Optional.of(user));

    Optional<UserDto> result = service.getUserByUsername(" Alice ");

    assertThat(result).isPresent();
  }

  @Test
  void getUsersByIds_returnsEmptyListForEmptyInput() {
    AuthServiceImpl service = service();

    assertThat(service.getUsersByIds(java.util.List.of())).isEmpty();
    verify(userRepository, never()).findByIdIn(any());
  }
}
