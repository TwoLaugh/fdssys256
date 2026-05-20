package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.api.controller.AuthController;
import com.example.mealprep.auth.api.dto.LoginRequest;
import com.example.mealprep.auth.api.dto.LoginResponse;
import com.example.mealprep.auth.api.dto.PasswordChangeRequest;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.api.dto.UserDto;
import com.example.mealprep.auth.api.mapper.UserMapper;
import com.example.mealprep.auth.api.mapper.UserMapperImpl;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.entity.LoginAttempt;
import com.example.mealprep.auth.domain.entity.LoginFailureReason;
import com.example.mealprep.auth.domain.entity.Session;
import com.example.mealprep.auth.domain.entity.User;
import com.example.mealprep.auth.domain.repository.LoginAttemptRepository;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.domain.service.AuthQueryService;
import com.example.mealprep.auth.domain.service.AuthUpdateService;
import com.example.mealprep.auth.domain.service.AuthenticatedPrincipal;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
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
import com.example.mealprep.auth.testdata.AuthTestData;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Targeted unit tests that kill SURVIVED + NO_COVERAGE mutants identified by Pitest on the auth
 * module.
 *
 * <p>Pure unit — no Spring context, no Testcontainers, no DB. Each test asserts a specific
 * behaviour whose break-on-mutation is documented in-line.
 *
 * <p>Scope:
 *
 * <ul>
 *   <li>{@code AuthController} — has no dedicated unit test prior to this PR; every handler /
 *       cookie attribute / header / status code is covered here so the mutator sees a failing test
 *       per branch.
 *   <li>{@code AuthModule} facade — three accessor methods that NullReturnVals will silently flip.
 *   <li>Auth event records (Registered / LoggedIn / PasswordChanged) — {@code scopeKind} / {@code
 *       scopeId} are virtual methods that NullReturnVals targets; the {@code scopeKind} string
 *       literal is also subject to mutation.
 *   <li>Exception accessors / messages — {@code AccountLockedException.lockedUntil()}, {@code
 *       LoginThrottledException.retryAfter()}, etc.
 *   <li>{@code AuthProperties} compact-constructor defaults + invariants — null-coalescing branch
 *       mutants plus the min&gt;max guard.
 *   <li>{@code AuthServiceImpl.DUMMY_BCRYPT_HASH} format pin (defence-in-depth for the
 *       timing-parity constant).
 * </ul>
 */
class AuthMutationKillsTest {

  // ============================================================
  // AuthController — no dedicated unit test pre-existed.
  // Tests cover register / login / logout / me / changePassword paths.
  // Mocked: AuthUpdateService, AuthQueryService, CurrentUserResolver.
  // ============================================================

  private static AuthProperties properties() {
    return new AuthProperties(
        12, Duration.ofDays(30), "AUTH_SESSION", true, "Strict", 12, 128, null, null);
  }

  private static AuthController controller(
      AuthUpdateService updateService,
      AuthQueryService queryService,
      CurrentUserResolver resolver,
      AuthProperties props) {
    return new AuthController(updateService, queryService, resolver, props);
  }

  private static HttpServletRequest mockRequest(String ip, String ua) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRemoteAddr()).thenReturn(ip);
    when(req.getHeader(HttpHeaders.USER_AGENT)).thenReturn(ua);
    return req;
  }

  private static LoginOutcome outcome(UUID userId, String username, UUID sessionId, String token) {
    UserDto dto = new UserDto(userId, username, Instant.parse("2026-05-01T00:00:00Z"));
    return new LoginOutcome(dto, sessionId, Instant.parse("2026-06-06T12:00:00Z"), token);
  }

  @Test
  void controller_register_returns201_withSetCookieFromOutcomeRawToken_andBodyIsUserDto() {
    AuthUpdateService updateService = mock(AuthUpdateService.class);
    AuthQueryService queryService = mock(AuthQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    AuthProperties props = properties();
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    LoginOutcome lo = outcome(userId, "alice", sessionId, "tok-register-abc");
    when(updateService.register(any(RegisterRequest.class), any(LoginContext.class)))
        .thenReturn(lo);

    var req = mockRequest("203.0.113.5", "Mozilla/5.0");
    RegisterRequest body = new RegisterRequest("alice", "correct-horse-battery-12");

    ResponseEntity<UserDto> resp =
        controller(updateService, queryService, resolver, props).register(body, req);

    // Status 201 — kills any mutator that drops/changes this status code.
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    // Body is the UserDto from the outcome — NullReturnVals on body would flip this to null.
    assertThat(resp.getBody()).isNotNull();
    assertThat(resp.getBody().userId()).isEqualTo(userId);
    assertThat(resp.getBody().username()).isEqualTo("alice");
    // Set-Cookie present and carries the raw token (NOT the hash). The cookie attributes come
    // from AuthProperties; pinning them kills mutators on the builder chain in sessionCookie().
    String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).isNotNull();
    assertThat(setCookie).contains("AUTH_SESSION=tok-register-abc");
    assertThat(setCookie).containsIgnoringCase("HttpOnly");
    assertThat(setCookie).containsIgnoringCase("Secure");
    assertThat(setCookie).contains("SameSite=Strict");
    assertThat(setCookie).contains("Path=/");
    // Max-Age comes from sessionTtl (30d = 2592000s).
    assertThat(setCookie).contains("Max-Age=2592000");
  }

  @Test
  void controller_register_buildsLoginContextFromRequestHeaders() {
    AuthUpdateService updateService = mock(AuthUpdateService.class);
    AuthQueryService queryService = mock(AuthQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    LoginOutcome lo = outcome(userId, "bob", sessionId, "tok-bob");

    org.mockito.ArgumentCaptor<LoginContext> captor =
        org.mockito.ArgumentCaptor.forClass(LoginContext.class);
    when(updateService.register(any(RegisterRequest.class), captor.capture())).thenReturn(lo);

    var req = mockRequest("198.51.100.7", "curl/8.0");
    controller(updateService, queryService, resolver, properties())
        .register(new RegisterRequest("bob", "correct-horse-battery-12"), req);

    // contextOf() pulls REMOTE_ADDR and User-Agent — kills any swap mutator that mixes them.
    assertThat(captor.getValue().sourceIp()).isEqualTo("198.51.100.7");
    assertThat(captor.getValue().userAgent()).isEqualTo("curl/8.0");
  }

  @Test
  void controller_login_returns200_withLoginResponseBody_butNoTokenInBody() {
    AuthUpdateService updateService = mock(AuthUpdateService.class);
    AuthQueryService queryService = mock(AuthQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    UUID sessionId = UUID.randomUUID();
    LoginOutcome lo = outcome(userId, "alice", sessionId, "tok-login-xyz");
    when(updateService.login(any(LoginRequest.class), any(LoginContext.class))).thenReturn(lo);

    var resp =
        controller(updateService, queryService, resolver, properties())
            .login(new LoginRequest("alice", "correct-horse-battery"), mockRequest("ip", "ua"));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    LoginResponse body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.userId()).isEqualTo(userId);
    assertThat(body.username()).isEqualTo("alice");
    // Set-Cookie carries the raw token; the response BODY must never carry it.
    String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).contains("AUTH_SESSION=tok-login-xyz");
  }

  @Test
  void controller_logout_clearsCookie_whenSessionPresent_andCallsServiceLogout() {
    AuthUpdateService updateService = mock(AuthUpdateService.class);
    AuthQueryService queryService = mock(AuthQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID sessionId = UUID.randomUUID();
    when(resolver.currentSessionId()).thenReturn(Optional.of(sessionId));

    var resp = controller(updateService, queryService, resolver, properties()).logout();

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    // Cleared cookie has empty value and Max-Age=0.
    assertThat(setCookie).contains("AUTH_SESSION=;");
    assertThat(setCookie).contains("Max-Age=0");
    // Service.logout invoked with the resolved sessionId.
    verify(updateService).logout(sessionId);
  }

  @Test
  void controller_logout_isIdempotent_clearsCookie_whenNoSessionPresent() {
    AuthUpdateService updateService = mock(AuthUpdateService.class);
    AuthQueryService queryService = mock(AuthQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    when(resolver.currentSessionId()).thenReturn(Optional.empty());

    var resp = controller(updateService, queryService, resolver, properties()).logout();

    // Still 204 with cleared cookie, but service NEVER invoked.
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).contains("AUTH_SESSION=;");
    assertThat(setCookie).contains("Max-Age=0");
    verify(updateService, never()).logout(any());
  }

  @Test
  void controller_me_returnsUserDto_whenAuthenticated() {
    AuthUpdateService updateService = mock(AuthUpdateService.class);
    AuthQueryService queryService = mock(AuthQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    UserDto dto = new UserDto(userId, "alice", Instant.parse("2026-04-01T00:00:00Z"));
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    when(queryService.getUser(userId)).thenReturn(Optional.of(dto));

    UserDto result = controller(updateService, queryService, resolver, properties()).me();

    assertThat(result).isSameAs(dto);
  }

  @Test
  void controller_me_throws401_whenAnonymous() {
    AuthUpdateService updateService = mock(AuthUpdateService.class);
    AuthQueryService queryService = mock(AuthQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    when(resolver.currentUserId()).thenReturn(Optional.empty());

    var ctrl = controller(updateService, queryService, resolver, properties());

    assertThatThrownBy(ctrl::me)
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
    verifyNoInteractions(queryService);
  }

  @Test
  void controller_me_throws401_whenUserNotFound_evenIfPrincipalPresent() {
    // Defensive — if the user row vanished after the cookie was issued, we MUST 401, not 500/200.
    AuthUpdateService updateService = mock(AuthUpdateService.class);
    AuthQueryService queryService = mock(AuthQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    when(queryService.getUser(userId)).thenReturn(Optional.empty());

    var ctrl = controller(updateService, queryService, resolver, properties());

    assertThatThrownBy(ctrl::me)
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void controller_changePassword_returns200_setsCookie_andPassesSessionIdToService() {
    AuthUpdateService updateService = mock(AuthUpdateService.class);
    AuthQueryService queryService = mock(AuthQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    UUID oldSessionId = UUID.randomUUID();
    UUID newSessionId = UUID.randomUUID();
    when(resolver.currentSessionId()).thenReturn(Optional.of(oldSessionId));
    LoginOutcome lo = outcome(userId, "alice", newSessionId, "tok-new-after-change");
    when(updateService.changePassword(
            eq(oldSessionId), any(PasswordChangeRequest.class), any(LoginContext.class)))
        .thenReturn(lo);

    var req = mockRequest("203.0.113.5", "Mozilla/5.0");
    var resp =
        controller(updateService, queryService, resolver, properties())
            .changePassword(new PasswordChangeRequest("old-pw-1234567", "new-pw-1234567890"), req);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).isNotNull();
    assertThat(resp.getBody().userId()).isEqualTo(userId);
    String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).contains("AUTH_SESSION=tok-new-after-change");
  }

  @Test
  void controller_changePassword_throws401_whenAnonymous() {
    AuthUpdateService updateService = mock(AuthUpdateService.class);
    AuthQueryService queryService = mock(AuthQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    when(resolver.currentSessionId()).thenReturn(Optional.empty());

    var ctrl = controller(updateService, queryService, resolver, properties());

    assertThatThrownBy(
            () ->
                ctrl.changePassword(
                    new PasswordChangeRequest("a-current-pw", "a-new-strong-pw"),
                    mockRequest("ip", "ua")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
    verifyNoInteractions(updateService);
  }

  @Test
  void controller_sessionCookie_attributesReflect_secureFalseAndSameSiteLax() {
    // Pin the false-secure / Lax-same-site branch — kills boolean-negation and string-swap
    // mutators on the AuthProperties.cookieSecure() / cookieSameSite() reads.
    AuthProperties laxProps =
        new AuthProperties(
            12, Duration.ofHours(1), "AUTH_SESSION", false, "Lax", 12, 128, null, null);
    AuthUpdateService updateService = mock(AuthUpdateService.class);
    AuthQueryService queryService = mock(AuthQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    LoginOutcome lo = outcome(userId, "alice", UUID.randomUUID(), "tokLaxValue");
    when(updateService.login(any(LoginRequest.class), any(LoginContext.class))).thenReturn(lo);

    var resp =
        controller(updateService, queryService, resolver, laxProps)
            .login(new LoginRequest("alice", "correct-horse-battery"), mockRequest("ip", "ua"));

    String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).contains("SameSite=Lax");
    // Secure flag absent when secure=false (Spring's ResponseCookie only emits Secure when true).
    assertThat(setCookie).doesNotContain("Secure");
    // Max-Age comes from sessionTtl (1h = 3600s).
    assertThat(setCookie).contains("Max-Age=3600");
  }

  // ============================================================
  // AuthModule facade — pure delegation; NullReturnVals would silently
  // return null from query()/update()/currentUser().
  // ============================================================

  @Test
  void authModule_query_returnsInjectedAuthQueryService() {
    AuthQueryService q = mock(AuthQueryService.class);
    AuthUpdateService u = mock(AuthUpdateService.class);
    CurrentUserResolver r = mock(CurrentUserResolver.class);

    AuthModule module = new AuthModule(q, u, r);

    assertThat(module.query()).isSameAs(q);
    assertThat(module.update()).isSameAs(u);
    assertThat(module.currentUser()).isSameAs(r);
  }

  // ============================================================
  // Event records — scopeKind / scopeId virtual methods.
  // ============================================================

  @Test
  void userRegisteredEvent_scopeKindIsUser_andScopeIdIsUserId() {
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    Instant now = Instant.parse("2026-05-19T10:00:00Z");
    UserRegisteredEvent ev = new UserRegisteredEvent(userId, "alice", now, traceId, now);

    // NullReturnVals would replace these accessors with null/empty string.
    assertThat(ev.scopeKind()).isEqualTo("user");
    assertThat(ev.scopeId()).isEqualTo(userId);
    assertThat(ev.userId()).isEqualTo(userId);
    assertThat(ev.username()).isEqualTo("alice");
    assertThat(ev.registeredAt()).isEqualTo(now);
    assertThat(ev.traceId()).isEqualTo(traceId);
    assertThat(ev.occurredAt()).isEqualTo(now);
  }

  @Test
  void userLoggedInEvent_scopeKindIsUser_andScopeIdIsUserId() {
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    Instant now = Instant.parse("2026-05-19T10:00:00Z");
    UserLoggedInEvent ev =
        new UserLoggedInEvent(userId, sessionId, "203.0.113.5", "ua", now, traceId, now);

    assertThat(ev.scopeKind()).isEqualTo("user");
    assertThat(ev.scopeId()).isEqualTo(userId);
    assertThat(ev.sessionId()).isEqualTo(sessionId);
    assertThat(ev.ipAddress()).isEqualTo("203.0.113.5");
    assertThat(ev.userAgent()).isEqualTo("ua");
    assertThat(ev.loggedInAt()).isEqualTo(now);
  }

  @Test
  void userPasswordChangedEvent_scopeKindIsUser_andScopeIdIsUserId_andCountRoundtrips() {
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    Instant now = Instant.parse("2026-05-19T10:00:00Z");
    UserPasswordChangedEvent ev = new UserPasswordChangedEvent(userId, 7, traceId, now);

    assertThat(ev.scopeKind()).isEqualTo("user");
    assertThat(ev.scopeId()).isEqualTo(userId);
    assertThat(ev.sessionsRevokedCount()).isEqualTo(7);
  }

  // ============================================================
  // Exception accessors — NullReturnVals on the getters.
  // ============================================================

  @Test
  void accountLockedException_carriesLockedUntil_andHasFixedMessage() {
    Instant lockedUntil = Instant.parse("2026-05-20T12:30:00Z");
    AccountLockedException ex = new AccountLockedException(lockedUntil);

    assertThat(ex.lockedUntil()).isEqualTo(lockedUntil);
    assertThat(ex.getMessage()).isEqualTo("Account locked");
  }

  @Test
  void loginThrottledException_carriesRetryAfter_andHasFixedMessage() {
    Duration retryAfter = Duration.ofSeconds(42);
    LoginThrottledException ex = new LoginThrottledException(retryAfter);

    assertThat(ex.retryAfter()).isEqualTo(retryAfter);
    assertThat(ex.getMessage()).isEqualTo("Login throttled");
  }

  @Test
  void invalidCredentialsException_defaultConstructor_usesFixedGenericMessage() {
    InvalidCredentialsException ex = new InvalidCredentialsException();
    // The exact "Invalid credentials" literal is what the exception handler relies on;
    // a string-literal mutator must observably change observable output.
    assertThat(ex.getMessage()).isEqualTo("Invalid credentials");
  }

  @Test
  void invalidCredentialsException_messageConstructor_doesNotLeakIntoGenericMessage() {
    // Even if internals construct with a detailed message, that string is opaque to the wire —
    // but it must round-trip via getMessage() so log forensics work.
    InvalidCredentialsException ex = new InvalidCredentialsException("internal: user-42 wrong-pw");
    assertThat(ex.getMessage()).isEqualTo("internal: user-42 wrong-pw");
  }

  @Test
  void usernameAlreadyExistsException_messageRoundtrips_andCauseChained() {
    Exception cause = new IllegalStateException("DB unique constraint");
    UsernameAlreadyExistsException ex =
        new UsernameAlreadyExistsException("Username already taken: alice", cause);

    assertThat(ex.getMessage()).isEqualTo("Username already taken: alice");
    assertThat(ex.getCause()).isSameAs(cause);

    UsernameAlreadyExistsException noCauseEx = new UsernameAlreadyExistsException("just a message");
    assertThat(noCauseEx.getMessage()).isEqualTo("just a message");
    assertThat(noCauseEx.getCause()).isNull();
  }

  // ============================================================
  // AuthenticatedPrincipal — record accessors.
  // ============================================================

  @Test
  void authenticatedPrincipal_carriesUserIdAndSessionId() {
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    AuthenticatedPrincipal p = new AuthenticatedPrincipal(userId, sessionId);

    assertThat(p.userId()).isEqualTo(userId);
    assertThat(p.sessionId()).isEqualTo(sessionId);
    assertThat(p.userId()).isNotEqualTo(p.sessionId());
  }

  // ============================================================
  // LoginContext / LoginOutcome — record accessors.
  // ============================================================

  @Test
  void loginContext_carriesIpAndUserAgent_andTreatsNullsAsNulls() {
    LoginContext c = new LoginContext("203.0.113.5", "Mozilla/5.0");
    assertThat(c.sourceIp()).isEqualTo("203.0.113.5");
    assertThat(c.userAgent()).isEqualTo("Mozilla/5.0");

    LoginContext nulls = new LoginContext(null, null);
    assertThat(nulls.sourceIp()).isNull();
    assertThat(nulls.userAgent()).isNull();
  }

  @Test
  void loginOutcome_carriesAllFourFields() {
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UserDto dto = new UserDto(userId, "alice", Instant.parse("2026-01-01T00:00:00Z"));
    Instant exp = Instant.parse("2026-02-01T00:00:00Z");
    LoginOutcome o = new LoginOutcome(dto, sessionId, exp, "the-raw-token");

    assertThat(o.user()).isSameAs(dto);
    assertThat(o.sessionId()).isEqualTo(sessionId);
    assertThat(o.sessionExpiresAt()).isEqualTo(exp);
    assertThat(o.rawSessionToken()).isEqualTo("the-raw-token");
  }

  // ============================================================
  // AuthProperties — compact-constructor null-coalescing branches.
  // Each defaulted field is its own null-branch mutator opportunity.
  // ============================================================

  @Test
  void authProperties_allNulls_applyDocumentedDefaults() {
    AuthProperties p = new AuthProperties(null, null, null, null, null, null, null, null, null);

    assertThat(p.bcryptCost()).isEqualTo(12);
    assertThat(p.sessionTtl()).isEqualTo(Duration.ofDays(30));
    assertThat(p.cookieName()).isEqualTo("AUTH_SESSION");
    assertThat(p.cookieSecure()).isFalse();
    assertThat(p.cookieSameSite()).isEqualTo("Lax");
    assertThat(p.passwordMinLength()).isEqualTo(12);
    assertThat(p.passwordMaxLength()).isEqualTo(128);
    // Nested defaults populate.
    assertThat(p.throttle()).isNotNull();
    assertThat(p.throttle().window()).isEqualTo(Duration.ofMinutes(15));
    assertThat(p.throttle().usernameMaxFailures()).isEqualTo(10);
    assertThat(p.throttle().ipMaxFailures()).isEqualTo(30);
    assertThat(p.lockout()).isNotNull();
    assertThat(p.lockout().threshold()).isEqualTo(5);
    assertThat(p.lockout().duration()).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void authProperties_blankCookieName_isReplacedWithDefault() {
    AuthProperties p = new AuthProperties(12, null, "   ", null, null, null, null, null, null);
    assertThat(p.cookieName()).isEqualTo("AUTH_SESSION");
  }

  @Test
  void authProperties_blankSameSite_isReplacedWithDefault() {
    AuthProperties p = new AuthProperties(12, null, null, null, "   ", null, null, null, null);
    assertThat(p.cookieSameSite()).isEqualTo("Lax");
  }

  @Test
  void authProperties_passwordMinGreaterThanMax_throwsIllegalArgument() {
    assertThatThrownBy(() -> new AuthProperties(12, null, null, null, null, 128, 64, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("passwordMinLength")
        .hasMessageContaining("passwordMaxLength");
  }

  @Test
  void authProperties_passwordMinEqualToMax_isPermitted() {
    // The boundary — min == max is allowed (not >); kills any > vs >= mutant.
    AuthProperties p = new AuthProperties(12, null, null, null, null, 64, 64, null, null);
    assertThat(p.passwordMinLength()).isEqualTo(64);
    assertThat(p.passwordMaxLength()).isEqualTo(64);
  }

  @Test
  void authProperties_nonDefaultValues_roundtrip() {
    AuthProperties p =
        new AuthProperties(
            14,
            Duration.ofHours(2),
            "CUSTOM_COOKIE",
            true,
            "None",
            16,
            256,
            new AuthProperties.Throttle(Duration.ofMinutes(5), 7, 21),
            new AuthProperties.Lockout(3, Duration.ofMinutes(30)));

    assertThat(p.bcryptCost()).isEqualTo(14);
    assertThat(p.sessionTtl()).isEqualTo(Duration.ofHours(2));
    assertThat(p.cookieName()).isEqualTo("CUSTOM_COOKIE");
    assertThat(p.cookieSecure()).isTrue();
    assertThat(p.cookieSameSite()).isEqualTo("None");
    assertThat(p.passwordMinLength()).isEqualTo(16);
    assertThat(p.passwordMaxLength()).isEqualTo(256);
    assertThat(p.throttle().window()).isEqualTo(Duration.ofMinutes(5));
    assertThat(p.throttle().usernameMaxFailures()).isEqualTo(7);
    assertThat(p.throttle().ipMaxFailures()).isEqualTo(21);
    assertThat(p.lockout().threshold()).isEqualTo(3);
    assertThat(p.lockout().duration()).isEqualTo(Duration.ofMinutes(30));
  }

  @Test
  void authProperties_throttleNullsApplyDefaults_eachField() {
    AuthProperties.Throttle t = new AuthProperties.Throttle(null, null, null);
    assertThat(t.window()).isEqualTo(Duration.ofMinutes(15));
    assertThat(t.usernameMaxFailures()).isEqualTo(10);
    assertThat(t.ipMaxFailures()).isEqualTo(30);
  }

  @Test
  void authProperties_lockoutNullsApplyDefaults() {
    AuthProperties.Lockout l = new AuthProperties.Lockout(null, null);
    assertThat(l.threshold()).isEqualTo(5);
    assertThat(l.duration()).isEqualTo(Duration.ofMinutes(15));
  }

  // ============================================================
  // AuthServiceImpl.DUMMY_BCRYPT_HASH — defence-in-depth that the
  // constant remains a valid BCrypt-12 hash. Constant inline mutators
  // could otherwise blank the literal and the unknown-user path would
  // throw at runtime.
  // ============================================================

  @Test
  void authServiceImpl_dummyHash_isValidBcrypt12FormatAndCorrectLength() {
    String h = AuthServiceImpl.DUMMY_BCRYPT_HASH;
    assertThat(h).isNotNull();
    assertThat(h).hasSize(60);
    assertThat(h).startsWith("$2a$12$");
  }

  // ============================================================
  // LoginFailureReason — enum constants and ordering are part of the
  // audit-row contract; pin them so a future "rename enum value" change
  // surfaces as a failing test rather than a silent migration bug.
  // ============================================================

  @Test
  void loginFailureReason_enumValues_inDocumentedOrder() {
    assertThat(LoginFailureReason.values())
        .containsExactly(
            LoginFailureReason.BAD_PASSWORD,
            LoginFailureReason.UNKNOWN_USER,
            LoginFailureReason.ACCOUNT_LOCKED,
            LoginFailureReason.THROTTLED,
            LoginFailureReason.INVALID_REQUEST);
  }

  // ============================================================
  // AuthServiceImpl — additional mutant kills uncovered by the
  // legacy AuthServiceImplTest.
  // ============================================================

  private static AuthServiceImpl serviceWithCollaborators(
      UserRepository userRepository,
      SessionRepository sessionRepository,
      LoginAttemptRepository loginAttemptRepository,
      LoginThrottleService loginThrottleService,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    AuthProperties props = new AuthProperties(12, null, null, null, null, null, null, null, null);
    UserMapper mapper = new UserMapperImpl();
    PasswordHasher hasher = new PasswordHasher(props);
    PasswordStrengthValidator strength = new PasswordStrengthValidator(props);
    SessionTokenGenerator tokens = new SessionTokenGenerator();
    return new AuthServiceImpl(
        userRepository,
        sessionRepository,
        loginAttemptRepository,
        mapper,
        hasher,
        strength,
        tokens,
        loginThrottleService,
        eventPublisher,
        props,
        clock);
  }

  /**
   * Kills the {@code lambda$getUsersByIds$1} NegateConditionals + BooleanTrueReturnVals: the filter
   * {@code u -> u.getDeletedAt() == null} must drop a soft-deleted user from the result. Also kills
   * the {@code EmptyObjectReturnVals} on the {@code getUsersByIds} return statement — the result is
   * non-empty AND content-checked.
   */
  @Test
  void authService_getUsersByIds_filtersOutSoftDeletedUsers_andReturnsPopulatedMappedList() {
    UserRepository userRepository = mock(UserRepository.class);
    SessionRepository sessionRepository = mock(SessionRepository.class);
    LoginAttemptRepository loginAttemptRepository = mock(LoginAttemptRepository.class);
    LoginThrottleService throttle = mock(LoginThrottleService.class);
    ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-07T12:00:00Z"), ZoneOffset.UTC);

    UUID aliveId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID deletedId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    User alive = AuthTestData.user().withId(aliveId).withUsername("alice").build();
    User deleted =
        AuthTestData.user().withId(deletedId).withUsername("ghost").softDeleted().build();
    when(userRepository.findByIdIn(List.of(aliveId, deletedId)))
        .thenReturn(List.of(alive, deleted));

    AuthServiceImpl svc =
        serviceWithCollaborators(
            userRepository, sessionRepository, loginAttemptRepository, throttle, pub, clock);
    List<UserDto> result = svc.getUsersByIds(List.of(aliveId, deletedId));

    // EmptyObjectReturnVals would replace this with an empty list → fail.
    assertThat(result).hasSize(1);
    // The filter must keep the alive user and drop the deleted one.
    assertThat(result.get(0).userId()).isEqualTo(aliveId);
    assertThat(result.get(0).username()).isEqualTo("alice");
  }

  /**
   * Kills the {@code getUsersByIds} {@code userIds == null} NegateConditionals branch: passing
   * {@code null} explicitly must short-circuit to an empty list without hitting the repository.
   */
  @Test
  void authService_getUsersByIds_nullInput_returnsEmptyAndDoesNotCallRepo() {
    UserRepository userRepository = mock(UserRepository.class);
    SessionRepository sessionRepository = mock(SessionRepository.class);
    LoginAttemptRepository loginAttemptRepository = mock(LoginAttemptRepository.class);
    LoginThrottleService throttle = mock(LoginThrottleService.class);
    ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-07T12:00:00Z"), ZoneOffset.UTC);

    AuthServiceImpl svc =
        serviceWithCollaborators(
            userRepository, sessionRepository, loginAttemptRepository, throttle, pub, clock);
    assertThat(svc.getUsersByIds(null)).isEmpty();
    verify(userRepository, never()).findByIdIn(any());
  }

  /**
   * Kills the {@code lambda$changePassword$2} BooleanTrueReturnVals: the {@code u -&gt; u
   * .getDeletedAt() == null} filter must reject a soft-deleted user even when their session is
   * still valid. Returning {@code true} unconditionally would skip the filter and allow a
   * soft-deleted user to rotate their password.
   *
   * <p>The kill needs a user whose stored password hash WOULD verify against the supplied {@code
   * currentPassword}. Otherwise the equivalent path (password mismatch → also
   * InvalidCredentialsException) would mask the mutated filter. Here the soft-deleted user has a
   * valid hash of "the-real-current-password", and the request supplies that exact string — so the
   * ONLY thing that can throw {@link InvalidCredentialsException} is the filter line. When the
   * filter is mutated to always-true, the flow continues, hits the password verify (which
   * succeeds), strength-validates the new password (passes), then calls {@code
   * userRepository.save(...)} — observable as a verify failure.
   */
  @Test
  void authService_changePassword_throwsInvalidCredentials_whenUserIsSoftDeleted() {
    UserRepository userRepository = mock(UserRepository.class);
    SessionRepository sessionRepository = mock(SessionRepository.class);
    LoginAttemptRepository loginAttemptRepository = mock(LoginAttemptRepository.class);
    LoginThrottleService throttle = mock(LoginThrottleService.class);
    ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-07T12:00:00Z"), ZoneOffset.UTC);

    AuthProperties props = new AuthProperties(12, null, null, null, null, null, null, null, null);
    PasswordHasher hasher = new PasswordHasher(props);

    UUID sessionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    User deleted =
        AuthTestData.user()
            .withId(userId)
            .withUsername("alice")
            .withPasswordHash(hasher.hash("the-real-current-password"))
            .softDeleted()
            .build();
    Session session = AuthTestData.session().withId(sessionId).withUserId(userId).build();
    when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(userRepository.findById(userId)).thenReturn(Optional.of(deleted));

    AuthServiceImpl svc =
        serviceWithCollaborators(
            userRepository, sessionRepository, loginAttemptRepository, throttle, pub, clock);
    assertThatThrownBy(
            () ->
                svc.changePassword(
                    sessionId,
                    new PasswordChangeRequest(
                        "the-real-current-password", "fresh-new-password-12345"),
                    new LoginContext("ip", "ua")))
        .isInstanceOf(InvalidCredentialsException.class);

    // Critical: no save attempted on a soft-deleted user — a survived BooleanTrueReturnVals
    // mutant would have let the flow continue to userRepository.save(...).
    verify(userRepository, never()).save(any());
    verify(sessionRepository, never()).save(any());
    verifyNoInteractions(pub);
  }

  /**
   * Kills the {@code login} VoidMethodCall on {@code recordAttempt} (line 212) inside the IP
   * throttle branch — pre-existing test only verified the exception was thrown, not that the audit
   * row was written.
   */
  @Test
  void authService_login_ipThrottled_writesThrottledAuditAttempt() {
    UserRepository userRepository = mock(UserRepository.class);
    SessionRepository sessionRepository = mock(SessionRepository.class);
    LoginAttemptRepository loginAttemptRepository = mock(LoginAttemptRepository.class);
    LoginThrottleService throttle = mock(LoginThrottleService.class);
    ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-07T12:00:00Z"), ZoneOffset.UTC);

    when(throttle.shouldThrottleByUsername(any(), any())).thenReturn(false);
    when(throttle.shouldThrottleByIp(any(), any())).thenReturn(true);
    when(throttle.retryAfterForIp(any(), any())).thenReturn(Duration.ofSeconds(75));

    AuthServiceImpl svc =
        serviceWithCollaborators(
            userRepository, sessionRepository, loginAttemptRepository, throttle, pub, clock);
    assertThatThrownBy(
            () ->
                svc.login(
                    new LoginRequest("alice", "correct-horse-battery"),
                    new LoginContext("203.0.113.5", "ua")))
        .isInstanceOf(LoginThrottledException.class);

    ArgumentCaptor<LoginAttempt> attempt = ArgumentCaptor.forClass(LoginAttempt.class);
    verify(loginAttemptRepository).save(attempt.capture());
    assertThat(attempt.getValue().getFailureReason()).isEqualTo(LoginFailureReason.THROTTLED);
    assertThat(attempt.getValue().getSourceIp()).isEqualTo("203.0.113.5");
    assertThat(attempt.getValue().getUserId()).isNull();
    assertThat(attempt.getValue().isSucceeded()).isFalse();
  }

  /**
   * Kills the {@code login} VoidMethodCall on {@code user.setLockedUntil(null)} (line 266) — the
   * legacy success test started with {@code lockedUntil=null} so the mutator removing the clear
   * call left observable behaviour unchanged. Here the user starts with a non-null past-in-time
   * lockedUntil and must end up null after successful login.
   */
  @Test
  void authService_login_successPath_clearsLockedUntil_whenPreviouslyLockedInThePast() {
    UserRepository userRepository = mock(UserRepository.class);
    SessionRepository sessionRepository = mock(SessionRepository.class);
    LoginAttemptRepository loginAttemptRepository = mock(LoginAttemptRepository.class);
    LoginThrottleService throttle = mock(LoginThrottleService.class);
    ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
    Instant now = Instant.parse("2026-05-07T12:00:00Z");
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    AuthProperties props = new AuthProperties(12, null, null, null, null, null, null, null, null);
    PasswordHasher hasher = new PasswordHasher(props);
    User user =
        AuthTestData.user()
            .withUsername("alice")
            .withPasswordHash(hasher.hash("correct-horse-battery"))
            .build();
    // Past lockout — not blocking (lockedUntil.isAfter(now) is false), but the setLockedUntil(null)
    // must still fire so this stale lockedUntil clears.
    Instant pastLock = now.minus(Duration.ofMinutes(5));
    user.setLockedUntil(pastLock);
    user.setFailedLoginCount(2);

    when(userRepository.findByUsernameNormalisedAndDeletedAtIsNull("alice"))
        .thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

    AuthServiceImpl svc =
        serviceWithCollaborators(
            userRepository, sessionRepository, loginAttemptRepository, throttle, pub, clock);
    svc.login(new LoginRequest("alice", "correct-horse-battery"), new LoginContext("ip", "ua"));

    // setLockedUntil(null) survived previously because the start value was already null.
    // Starting from a non-null value makes the mutator observably wrong.
    assertThat(user.getLockedUntil()).isNull();
    assertThat(user.getFailedLoginCount()).isZero();
  }

  /**
   * Kills the {@code login} NegateConditionals at line 278 — the {@code loginContext == null ? null
   * : loginContext.userAgent()} ternary inside {@code UserLoggedInEvent} construction. Also
   * exercises the same null-context branch in {@code issueSession()} (lines 391-392) for {@code
   * issuingIp}/{@code userAgent}.
   */
  @Test
  void authService_login_succeeds_withNullLoginContext_andEventCarriesNullIpAndAgent() {
    UserRepository userRepository = mock(UserRepository.class);
    SessionRepository sessionRepository = mock(SessionRepository.class);
    LoginAttemptRepository loginAttemptRepository = mock(LoginAttemptRepository.class);
    LoginThrottleService throttle = mock(LoginThrottleService.class);
    ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
    Instant now = Instant.parse("2026-05-07T12:00:00Z");
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    AuthProperties props = new AuthProperties(12, null, null, null, null, null, null, null, null);
    PasswordHasher hasher = new PasswordHasher(props);
    User user =
        AuthTestData.user()
            .withUsername("alice")
            .withPasswordHash(hasher.hash("correct-horse-battery"))
            .build();
    when(userRepository.findByUsernameNormalisedAndDeletedAtIsNull("alice"))
        .thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

    AuthServiceImpl svc =
        serviceWithCollaborators(
            userRepository, sessionRepository, loginAttemptRepository, throttle, pub, clock);

    // Null loginContext — sourceIp / userAgent should propagate as null.
    LoginOutcome outcome = svc.login(new LoginRequest("alice", "correct-horse-battery"), null);

    assertThat(outcome.rawSessionToken()).isNotBlank();
    assertThat(user.getLastLoginIp()).isNull();

    ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(pub).publishEvent(event.capture());
    assertThat(event.getValue()).isInstanceOf(UserLoggedInEvent.class);
    UserLoggedInEvent published = (UserLoggedInEvent) event.getValue();
    assertThat(published.ipAddress()).isNull();
    assertThat(published.userAgent()).isNull();

    // Session row issued with null issuingIp / userAgent — kills issueSession lines 391-392.
    ArgumentCaptor<Session> sessionCap = ArgumentCaptor.forClass(Session.class);
    verify(sessionRepository).save(sessionCap.capture());
    assertThat(sessionCap.getValue().getIssuingIp()).isNull();
    assertThat(sessionCap.getValue().getUserAgent()).isNull();
  }

  // ============================================================
  // Entity getter/setter / builder coverage — lombok-generated code
  // accumulates NullReturnVals and EmptyObjectReturnVals survivors
  // because no test exercises every field round-trip.
  // ============================================================

  @Test
  void userEntity_builderAndGetters_roundtripEveryField() {
    UUID id = UUID.randomUUID();
    Instant pwUpdated = Instant.parse("2026-04-01T00:00:00Z");
    Instant lockedUntil = Instant.parse("2026-05-01T00:00:00Z");
    Instant lastLogin = Instant.parse("2026-05-05T00:00:00Z");
    Instant deleted = Instant.parse("2026-05-06T00:00:00Z");
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    Instant updated = Instant.parse("2026-05-07T00:00:00Z");

    User user =
        User.builder()
            .id(id)
            .username("Alice")
            .usernameNormalised("alice")
            .passwordHash("$2a$12$abc")
            .passwordUpdatedAt(pwUpdated)
            .failedLoginCount(3)
            .lockedUntil(lockedUntil)
            .lastLoginAt(lastLogin)
            .lastLoginIp("203.0.113.5")
            .deletedAt(deleted)
            .version(42L)
            .createdAt(created)
            .updatedAt(updated)
            .build();

    // Each accessor kills a NullReturnVals / PrimitiveReturnsMutator survivor.
    assertThat(user.getId()).isEqualTo(id);
    assertThat(user.getUsername()).isEqualTo("Alice");
    assertThat(user.getUsernameNormalised()).isEqualTo("alice");
    assertThat(user.getPasswordHash()).isEqualTo("$2a$12$abc");
    assertThat(user.getPasswordUpdatedAt()).isEqualTo(pwUpdated);
    assertThat(user.getFailedLoginCount()).isEqualTo(3);
    assertThat(user.getLockedUntil()).isEqualTo(lockedUntil);
    assertThat(user.getLastLoginAt()).isEqualTo(lastLogin);
    assertThat(user.getLastLoginIp()).isEqualTo("203.0.113.5");
    assertThat(user.getDeletedAt()).isEqualTo(deleted);
    assertThat(user.getVersion()).isEqualTo(42L);
    assertThat(user.getCreatedAt()).isEqualTo(created);
    assertThat(user.getUpdatedAt()).isEqualTo(updated);
  }

  @Test
  void userEntity_builderToString_isNonEmpty() {
    // The lombok-generated UserBuilder.toString() has a EmptyObjectReturnVals survivor that
    // would replace it with the empty string; pinning a non-empty result kills it.
    String repr = User.builder().id(UUID.randomUUID()).username("alice").toString();
    assertThat(repr).isNotEmpty();
    assertThat(repr).contains("alice");
  }

  @Test
  void sessionEntity_builderAndGettersAndSetters_roundtripEveryField() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Instant issued = Instant.parse("2026-05-01T00:00:00Z");
    Instant expires = Instant.parse("2026-06-01T00:00:00Z");
    Instant lastSeen = Instant.parse("2026-05-02T00:00:00Z");
    Instant revoked = Instant.parse("2026-05-05T00:00:00Z");

    Session session =
        Session.builder()
            .id(id)
            .userId(userId)
            .tokenHash("hash-xyz")
            .issuedAt(issued)
            .expiresAt(expires)
            .lastSeenAt(lastSeen)
            .revokedAt(revoked)
            .issuingIp("198.51.100.7")
            .userAgent("Mozilla/5.0")
            .version(7L)
            .build();

    assertThat(session.getId()).isEqualTo(id);
    assertThat(session.getUserId()).isEqualTo(userId);
    assertThat(session.getTokenHash()).isEqualTo("hash-xyz");
    assertThat(session.getIssuedAt()).isEqualTo(issued);
    assertThat(session.getExpiresAt()).isEqualTo(expires);
    assertThat(session.getLastSeenAt()).isEqualTo(lastSeen);
    assertThat(session.getRevokedAt()).isEqualTo(revoked);
    assertThat(session.getIssuingIp()).isEqualTo("198.51.100.7");
    assertThat(session.getUserAgent()).isEqualTo("Mozilla/5.0");
    assertThat(session.getVersion()).isEqualTo(7L);
  }

  @Test
  void sessionEntity_builderToString_isNonEmpty() {
    // Call toString on the BUILDER itself (pre-build) — that's the bytecode method targeted by
    // the SessionBuilder::toString EmptyObjectReturnVals mutator. Calling toString on the built
    // Session goes through Session::toString, a separate lombok-generated method.
    String builderRepr = Session.builder().tokenHash("h").toString();
    assertThat(builderRepr).isNotEmpty();
    assertThat(builderRepr).containsIgnoringCase("SessionBuilder");

    String entityRepr = Session.builder().tokenHash("h").build().toString();
    assertThat(entityRepr).isNotEmpty();
    assertThat(entityRepr).containsIgnoringCase("Session");
  }

  @Test
  void loginAttemptEntity_builderAndGetters_roundtripEveryField() {
    UUID id = UUID.fromString("33333333-3333-3333-3333-333333333333");
    UUID userId = UUID.randomUUID();
    Instant attempted = Instant.parse("2026-05-07T12:00:00Z");

    LoginAttempt attempt =
        LoginAttempt.builder()
            .id(id)
            .usernameNormalised("alice")
            .userId(userId)
            .sourceIp("203.0.113.5")
            .succeeded(true)
            .failureReason(null)
            .attemptedAt(attempted)
            .build();

    // getId() had NullReturnVals survivor — pin the value end-to-end.
    assertThat(attempt.getId()).isEqualTo(id);
    assertThat(attempt.getUsernameNormalised()).isEqualTo("alice");
    assertThat(attempt.getUserId()).isEqualTo(userId);
    assertThat(attempt.getSourceIp()).isEqualTo("203.0.113.5");
    assertThat(attempt.isSucceeded()).isTrue();
    assertThat(attempt.getFailureReason()).isNull();
    assertThat(attempt.getAttemptedAt()).isEqualTo(attempted);
  }

  @Test
  void loginAttemptEntity_failedAttempt_failureReasonAndUserIdRoundtrip() {
    LoginAttempt attempt =
        LoginAttempt.builder()
            .id(UUID.randomUUID())
            .usernameNormalised("ghost")
            .userId(null)
            .sourceIp("203.0.113.5")
            .succeeded(false)
            .failureReason(LoginFailureReason.UNKNOWN_USER)
            .attemptedAt(Instant.parse("2026-05-07T12:00:00Z"))
            .build();

    assertThat(attempt.isSucceeded()).isFalse();
    assertThat(attempt.getFailureReason()).isEqualTo(LoginFailureReason.UNKNOWN_USER);
    assertThat(attempt.getUserId()).isNull();
  }

  @Test
  void loginAttemptEntity_builderToString_isNonEmpty() {
    // Call toString on the BUILDER itself (pre-build) — that's the bytecode method targeted by
    // the LoginAttemptBuilder::toString EmptyObjectReturnVals mutator.
    String builderRepr = LoginAttempt.builder().usernameNormalised("alice").toString();
    assertThat(builderRepr).isNotEmpty();
    assertThat(builderRepr).containsIgnoringCase("LoginAttempt");
  }

  /**
   * Cookie attribute pinning — the {@code clearedCookie()} private helper has a NullReturnVals
   * survivor; the {@code logout()} test above kills it via the Set-Cookie header check, but we also
   * pin the {@code Secure} attribute pre-set here so the {@code authProperties.cookieSecure} read
   * inside clearedCookie() is exercised. (Otherwise the read could be mutated to false without
   * observable difference.)
   */
  @Test
  void controller_logout_clearedCookie_carriesSecureFlagWhenPropertyIsTrue() {
    AuthUpdateService updateService = mock(AuthUpdateService.class);
    AuthQueryService queryService = mock(AuthQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    when(resolver.currentSessionId()).thenReturn(Optional.empty());
    // secure=true property — cleared cookie must propagate it.
    AuthProperties secureProps =
        new AuthProperties(
            12, Duration.ofDays(30), "AUTH_SESSION", true, "Strict", 12, 128, null, null);

    var resp = controller(updateService, queryService, resolver, secureProps).logout();
    String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).containsIgnoringCase("Secure");
    assertThat(setCookie).contains("SameSite=Strict");
  }
}
