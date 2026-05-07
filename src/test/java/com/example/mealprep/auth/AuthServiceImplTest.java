package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.api.dto.LoginRequest;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.api.dto.UserDto;
import com.example.mealprep.auth.api.mapper.UserMapper;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.entity.Session;
import com.example.mealprep.auth.domain.entity.User;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.domain.service.LoginContext;
import com.example.mealprep.auth.domain.service.LoginOutcome;
import com.example.mealprep.auth.domain.service.internal.AuthServiceImpl;
import com.example.mealprep.auth.domain.service.internal.PasswordHasher;
import com.example.mealprep.auth.domain.service.internal.PasswordStrengthValidator;
import com.example.mealprep.auth.domain.service.internal.SessionTokenGenerator;
import com.example.mealprep.auth.event.UserLoggedInEvent;
import com.example.mealprep.auth.event.UserRegisteredEvent;
import com.example.mealprep.auth.exception.InvalidCredentialsException;
import com.example.mealprep.auth.exception.UsernameAlreadyExistsException;
import com.example.mealprep.auth.testdata.AuthTestData;
import java.time.Clock;
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
 * are real (cheap, no I/O); repositories and event publisher are mocked because they are at the
 * module boundary.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

  @Mock private UserRepository userRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final AuthProperties properties =
      new AuthProperties(12, null, null, null, null, null, null, null, null);
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
        userMapper,
        passwordHasher,
        strengthValidator,
        tokenGenerator,
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
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MATCHES_USERNAME");

    verify(userRepository, never()).saveAndFlush(any());
  }

  // ---------------- login ----------------

  @Test
  void login_succeeds_andResetsFailedCount_andSetsLastLogin() {
    AuthServiceImpl service = service();
    User user =
        AuthTestData.user()
            .withUsername("alice")
            .withPasswordHash(passwordHasher.hash("correct-horse-battery"))
            .build();
    user.setFailedLoginCount(3);
    when(userRepository.findByUsernameNormalisedAndDeletedAtIsNull("alice"))
        .thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

    LoginOutcome outcome =
        service.login(
            new LoginRequest("alice", "correct-horse-battery"),
            new LoginContext("203.0.113.5", "ua"));

    assertThat(user.getFailedLoginCount()).isZero();
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
  }

  @Test
  void login_throwsInvalidCredentials_andSuppressesEvent_forUnknownUser() {
    AuthServiceImpl service = service();
    when(userRepository.findByUsernameNormalisedAndDeletedAtIsNull("alice"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.login(
                    new LoginRequest("alice", "correct-horse-battery"),
                    new LoginContext("ip", "ua")))
        .isInstanceOf(InvalidCredentialsException.class)
        .hasMessage("Invalid credentials");

    verify(sessionRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
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

    assertThatThrownBy(
            () ->
                service.login(
                    new LoginRequest("alice", "the-wrong-password"), new LoginContext("ip", "ua")))
        .isInstanceOf(InvalidCredentialsException.class)
        .hasMessage("Invalid credentials");

    verify(sessionRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
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
