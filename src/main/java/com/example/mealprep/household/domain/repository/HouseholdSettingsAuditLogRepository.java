package com.example.mealprep.household.domain.repository;

import com.example.mealprep.household.domain.entity.HouseholdSettingsAuditLog;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link HouseholdSettingsAuditLog}. Cross-module callers go through
 * {@code HouseholdQueryService} (audit-log read) / {@code HouseholdUpdateService} (write side via
 * {@code updateSettings}). See {@link HouseholdSettingsRepository} for visibility note.
 */
public interface HouseholdSettingsAuditLogRepository
    extends JpaRepository<HouseholdSettingsAuditLog, UUID> {

  Page<HouseholdSettingsAuditLog> findByHouseholdSettingsIdOrderByOccurredAtDesc(
      UUID householdSettingsId, Pageable pageable);
}
