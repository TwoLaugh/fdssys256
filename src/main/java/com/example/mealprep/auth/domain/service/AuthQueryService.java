package com.example.mealprep.auth.domain.service;

import com.example.mealprep.auth.api.dto.UserDto;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read API for the auth module. Cross-module callers inject this; never the repositories. */
public interface AuthQueryService {

  /** Returns the user iff present AND not soft-deleted. */
  Optional<UserDto> getUser(UUID userId);

  /** Batch sibling per the style guide's "every single-id getter has a batch sibling" rule. */
  List<UserDto> getUsersByIds(Collection<UUID> userIds);

  /**
   * Look up by the case-insensitive username. Returns empty for unknown OR soft-deleted users — the
   * controller path treats both as "not authenticatable".
   */
  Optional<UserDto> getUserByUsername(String username);
}
