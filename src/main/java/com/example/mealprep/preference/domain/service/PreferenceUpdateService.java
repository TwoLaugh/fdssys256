package com.example.mealprep.preference.domain.service;

import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import java.util.UUID;

/**
 * Write API for the preference module — partial in 01a (hard-constraints only). Taste profile and
 * lifestyle config writes land in subsequent preference tickets.
 */
public interface PreferenceUpdateService {

  /**
   * Create the hard-constraints aggregate for a freshly-registered user with sensible defaults
   * ({@code base = "omnivore"}, empty children). Called from {@code auth} at user-creation; not
   * exposed via REST.
   */
  HardConstraintsDto initialiseHardConstraints(UUID userId);

  /**
   * Replace the user's hard-constraints aggregate. Each field is diffed against the existing row;
   * one audit-log entry is written per actually-changed field. Throws {@code
   * HardConstraintsNotFoundException} if no row exists; bumps {@code @Version} (mismatch surfaces
   * as {@code OptimisticLockingFailureException} → 409).
   */
  HardConstraintsDto updateHardConstraints(
      UUID userId, UpdateHardConstraintsRequest request, UUID actorUserId);
}
