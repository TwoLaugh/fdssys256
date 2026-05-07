package com.example.mealprep.auth.api.controller;

import com.example.mealprep.auth.api.dto.LoginRequest;
import com.example.mealprep.auth.api.dto.LoginResponse;
import com.example.mealprep.auth.api.dto.PasswordChangeRequest;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.api.dto.UserDto;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.service.AuthQueryService;
import com.example.mealprep.auth.domain.service.AuthUpdateService;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.auth.domain.service.LoginContext;
import com.example.mealprep.auth.domain.service.LoginOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the auth module. Delegates all logic to {@link AuthUpdateService} / {@link
 * AuthQueryService}; this class only handles HTTP shape (cookie attributes, status codes, principal
 * extraction).
 *
 * <p>The raw session token is touched here ONLY to write {@code Set-Cookie}. It never appears in a
 * response body, never gets logged.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {

  private final AuthUpdateService authUpdateService;
  private final AuthQueryService authQueryService;
  private final CurrentUserResolver currentUserResolver;
  private final AuthProperties authProperties;

  public AuthController(
      AuthUpdateService authUpdateService,
      AuthQueryService authQueryService,
      CurrentUserResolver currentUserResolver,
      AuthProperties authProperties) {
    this.authUpdateService = authUpdateService;
    this.authQueryService = authQueryService;
    this.currentUserResolver = currentUserResolver;
    this.authProperties = authProperties;
  }

  @PostMapping(
      path = "/register",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Create a new user account and auto-log in.")
  public ResponseEntity<UserDto> register(
      @Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
    LoginOutcome outcome = authUpdateService.register(request, contextOf(httpRequest));
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.SET_COOKIE, sessionCookie(outcome).toString())
        .body(outcome.user());
  }

  @PostMapping(
      path = "/login",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Authenticate; receive session cookie.")
  public ResponseEntity<LoginResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
    LoginOutcome outcome = authUpdateService.login(request, contextOf(httpRequest));
    LoginResponse body = new LoginResponse(outcome.user().userId(), outcome.user().username());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessionCookie(outcome).toString())
        .body(body);
  }

  @PostMapping(path = "/logout")
  @Operation(summary = "Revoke the current session.")
  public ResponseEntity<Void> logout() {
    Optional<UUID> sessionId = currentUserResolver.currentSessionId();
    sessionId.ifPresent(authUpdateService::logout);
    // Idempotent — even if there was no session in the SecurityContext, we still clear the cookie.
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
        .build();
  }

  @GetMapping(path = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Probe authentication state.")
  public UserDto me() {
    UUID userId =
        currentUserResolver
            .currentUserId()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authentication required."));
    return authQueryService
        .getUser(userId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }

  @PutMapping(
      path = "/password",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Rotate password; revoke other sessions; re-issue current.")
  public ResponseEntity<UserDto> changePassword(
      @Valid @RequestBody PasswordChangeRequest request, HttpServletRequest httpRequest) {
    UUID currentSessionId =
        currentUserResolver
            .currentSessionId()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authentication required."));
    LoginOutcome outcome =
        authUpdateService.changePassword(currentSessionId, request, contextOf(httpRequest));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessionCookie(outcome).toString())
        .body(outcome.user());
  }

  // ---------------- Helpers ----------------

  private LoginContext contextOf(HttpServletRequest request) {
    return new LoginContext(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
  }

  private ResponseCookie sessionCookie(LoginOutcome outcome) {
    return ResponseCookie.from(authProperties.cookieName(), outcome.rawSessionToken())
        .httpOnly(true)
        .secure(authProperties.cookieSecure())
        .sameSite(authProperties.cookieSameSite())
        .path("/")
        .maxAge(authProperties.sessionTtl())
        .build();
  }

  private ResponseCookie clearedCookie() {
    return ResponseCookie.from(authProperties.cookieName(), "")
        .httpOnly(true)
        .secure(authProperties.cookieSecure())
        .sameSite(authProperties.cookieSameSite())
        .path("/")
        .maxAge(0)
        .build();
  }
}
