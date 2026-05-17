package com.example.mealprep.discovery.domain.repository;

import com.example.mealprep.discovery.domain.entity.DiscoveryGoogleCseUsage;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link DiscoveryGoogleCseUsage}. Package-private — only {@code
 * GoogleCseDailyQuotaTracker} (same module) uses it; cross-module access is not a concern (this is
 * internal quota bookkeeping with no DTO surface in 01e).
 *
 * <p>{@code findById(LocalDate)} / {@code save} from {@link JpaRepository} cover the read-on-start
 * + per-call upsert; no derived queries needed.
 */
public interface DiscoveryGoogleCseUsageRepository
    extends JpaRepository<DiscoveryGoogleCseUsage, LocalDate> {}
