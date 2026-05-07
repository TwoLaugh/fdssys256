package com.example.mealprep.auth;

import com.example.mealprep.auth.domain.service.AuthQueryService;
import com.example.mealprep.auth.domain.service.AuthUpdateService;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the auth module's public service interfaces. Cross-module callers
 * inject this (or an individual service) rather than reaching into {@code domain.service.*}
 * directly.
 *
 * <p>Mirrors {@code core.CoreModule}; thin and carries no business logic.
 */
@Component
public class AuthModule {

  private final AuthQueryService authQueryService;
  private final AuthUpdateService authUpdateService;
  private final CurrentUserResolver currentUserResolver;

  public AuthModule(
      AuthQueryService authQueryService,
      AuthUpdateService authUpdateService,
      CurrentUserResolver currentUserResolver) {
    this.authQueryService = authQueryService;
    this.authUpdateService = authUpdateService;
    this.currentUserResolver = currentUserResolver;
  }

  public AuthQueryService query() {
    return authQueryService;
  }

  public AuthUpdateService update() {
    return authUpdateService;
  }

  public CurrentUserResolver currentUser() {
    return currentUserResolver;
  }
}
