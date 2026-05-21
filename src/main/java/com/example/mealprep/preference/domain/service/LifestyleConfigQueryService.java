package com.example.mealprep.preference.domain.service;

import com.example.mealprep.preference.api.dto.LifestyleConfigAuditEntryDto;
import com.example.mealprep.preference.api.dto.LifestyleConfigDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read API for the Tier-3 lifestyle config aggregate. Distinct from {@code PreferenceQueryService}
 * (Tier 1 hard constraints) so the two surfaces stay loosely coupled; both are owned by the same
 * implementation class is left to the impl package's discretion.
 */
public interface LifestyleConfigQueryService {

  /** Returns the user's lifestyle-config aggregate, or empty if not yet initialised. */
  Optional<LifestyleConfigDto> getLifestyleConfig(UUID userId);

  /** Bulk read by user ids; ordering not guaranteed. Empty if none of the users have a row. */
  List<LifestyleConfigDto> getLifestyleConfigsByUserIds(List<UUID> userIds);

  /**
   * Paginated audit-log entries for the user's lifestyle config, newest-first. Returns empty page
   * if the user has no aggregate row.
   */
  Page<LifestyleConfigAuditEntryDto> getAuditLog(UUID userId, Pageable pageable);

  /**
   * Section-filtered audit log; returns rows where {@code fieldPath = sectionPath} or starts with
   * {@code sectionPath + "."}. Returns empty page if the user has no aggregate row.
   */
  Page<LifestyleConfigAuditEntryDto> getAuditLogForSection(
      UUID userId, String sectionPath, Pageable pageable);
}
