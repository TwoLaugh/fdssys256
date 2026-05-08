package com.example.mealprep.preference.domain.service;

import com.example.mealprep.preference.api.dto.HardConstraintsAuditEntryDto;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read API for the preference module — partial in 01a (hard-constraints only). Taste profile,
 * lifestyle config, profile metadata, and the soft bundle land in subsequent preference tickets.
 */
public interface PreferenceQueryService {

  /** Returns the user's hard-constraints aggregate, or empty if not yet initialised. */
  Optional<HardConstraintsDto> getHardConstraints(UUID userId);

  /**
   * Paginated audit-log entries for the user's hard-constraints aggregate, newest-first. Returns
   * empty page if the user has no aggregate row.
   */
  Page<HardConstraintsAuditEntryDto> getHardConstraintsAuditLog(UUID userId, Pageable pageable);
}
