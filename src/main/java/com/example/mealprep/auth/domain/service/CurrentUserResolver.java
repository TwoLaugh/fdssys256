package com.example.mealprep.auth.domain.service;

import java.util.Optional;
import java.util.UUID;

/**
 * The single boundary other modules use to read the current authenticated {@code userId}. Cross-
 * module callers inject this rather than reaching for {@code SecurityContextHolder} or any Spring
 * Security type — the auth module owns the dependency.
 */
public interface CurrentUserResolver {

  /** Returns the current user's id, or empty for anonymous requests. */
  Optional<UUID> currentUserId();

  /**
   * Returns the current session id (the row backing the active cookie), or empty for anonymous
   * requests. Useful to {@code AuthController.logout}, which needs to know which session to revoke.
   */
  Optional<UUID> currentSessionId();
}
