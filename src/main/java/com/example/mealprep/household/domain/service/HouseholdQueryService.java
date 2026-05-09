package com.example.mealprep.household.domain.service;

import com.example.mealprep.household.api.dto.HouseholdDto;
import java.util.Optional;
import java.util.UUID;

/**
 * Read API for the household module — partial in 01a (root + members only). Settings, invites, and
 * audit-log lookups land in 01b/01c.
 */
public interface HouseholdQueryService {

  /** Look up a household by id, with members eagerly populated. */
  Optional<HouseholdDto> getById(UUID householdId);

  /**
   * Read-by-others contract: return the (single) household this user belongs to, or empty if they
   * are not in any household. v1 invariant: at most one household per user.
   */
  Optional<HouseholdDto> getByUserId(UUID userId);
}
