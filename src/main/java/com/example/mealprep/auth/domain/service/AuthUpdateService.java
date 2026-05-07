package com.example.mealprep.auth.domain.service;

import com.example.mealprep.auth.api.dto.LoginRequest;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import java.util.UUID;

/** Write API for the auth module — register / login / logout. */
public interface AuthUpdateService {

  /**
   * Create a user and auto-issue a session. Throws {@code UsernameAlreadyExistsException} on
   * uniqueness collision (mapped to 409).
   */
  LoginOutcome register(RegisterRequest request, LoginContext loginContext);

  /**
   * Verify credentials and issue a session. Throws {@code InvalidCredentialsException} on either
   * unknown-user or wrong-password (mapped to a generic 401 — never disambiguated).
   */
  LoginOutcome login(LoginRequest request, LoginContext loginContext);

  /**
   * Mark the session row revoked. Idempotent: a second call against an already-revoked session is a
   * no-op and the controller still returns 204.
   */
  void logout(UUID sessionId);
}
