package com.example.mealprep.preference.domain.service;

import com.example.mealprep.preference.api.dto.PreferenceArchiveEntryDto;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read surface over the preference archive. Public — re-exported via {@code PreferenceModule}.
 * Backs the user-facing "preference history" view and the AI delta-update task's re-emergence
 * detection.
 */
public interface PreferenceArchiveQueryService {

  /** User-facing archive listing for {@code userId}, newest-archived first. */
  Page<PreferenceArchiveEntryDto> getArchive(UUID userId, Pageable pageable);

  /** Archive listing filtered to entries whose {@code fieldPath} starts with the given prefix. */
  Page<PreferenceArchiveEntryDto> getArchiveForField(
      UUID userId, String fieldPathPrefix, Pageable pageable);

  /** Full snapshot for the AI delta-update task per {@code lld/preference.md:534-535}. */
  List<PreferenceArchiveEntryDto> getFullArchive(UUID userId);

  /** Active entries (not yet re-promoted) — used by analytics. */
  long countActiveEntries(UUID userId);
}
