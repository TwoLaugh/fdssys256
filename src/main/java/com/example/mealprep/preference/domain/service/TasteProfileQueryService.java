package com.example.mealprep.preference.domain.service;

import com.example.mealprep.preference.api.dto.TasteProfileAuditEntryDto;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.api.dto.TasteProfileVersionDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read API for the taste profile aggregate. Re-exported via {@code PreferenceModule#tasteProfile()}
 * so cross-module callers (planner, optimiser, household merge) inject the narrower interface
 * rather than the impl.
 *
 * <p>01a/01b's {@code PreferenceQueryService} is intentionally kept narrow to hard constraints; the
 * taste profile is a structurally different aggregate (single JSONB document vs four-table
 * aggregate) and warrants its own service interface.
 */
public interface TasteProfileQueryService {

  /** Returns the user's taste profile, or empty if not yet initialised. */
  Optional<TasteProfileDto> getTasteProfile(UUID userId);

  /** Batch read for the planner / household merge. Empty list when no users have profiles. */
  List<TasteProfileDto> getTasteProfilesByUserIds(List<UUID> userIds);

  /**
   * Paginated version history, newest document_version first. Empty page when user has no profile.
   */
  Page<TasteProfileVersionDto> getVersions(UUID userId, Pageable pageable);

  /** Returns the snapshot at {@code documentVersion}, or empty if no such version. */
  Optional<TasteProfileVersionDto> getVersion(UUID userId, int documentVersion);

  /** Paginated audit log of taste-profile changes, newest occurred_at first. */
  Page<TasteProfileAuditEntryDto> getAuditLog(UUID userId, Pageable pageable);
}
