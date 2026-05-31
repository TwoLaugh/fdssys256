package com.example.mealprep.ops.domain.service;

import com.example.mealprep.ai.domain.service.AiOperationalStatusService;
import com.example.mealprep.nutrition.domain.service.NutritionOperationalStatusService;
import com.example.mealprep.ops.api.dto.AdminStatusDto;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Assembles the {@link AdminStatusDto} for {@code GET /api/v1/admin/status} (capability C-G-032) by
 * aggregating cross-module operational signals: database connectivity (a validation probe against
 * the primary {@link DataSource}), the AI module's last-call timestamp + month-to-date spend, and
 * the nutrition module's last-USDA-call timestamp.
 *
 * <p>Lives in the leaf {@code ops} module — it depends on the {@code ai} and {@code nutrition}
 * public SPIs and {@code core}/datasource, and nothing depends back on it, so the cross-module
 * fan-out does not invert any boundary (in particular {@code core} stays free of {@code ai} /
 * {@code nutrition} dependencies).
 */
@Service
public class AdminStatusService {

  private static final Logger log = LoggerFactory.getLogger(AdminStatusService.class);

  /** Seconds the JDBC driver may take to validate the connection before treating it as down. */
  private static final int DB_VALIDATION_TIMEOUT_SECONDS = 2;

  private final DataSource dataSource;
  private final AiOperationalStatusService aiStatus;
  private final NutritionOperationalStatusService nutritionStatus;
  private final Clock clock;

  public AdminStatusService(
      DataSource dataSource,
      AiOperationalStatusService aiStatus,
      NutritionOperationalStatusService nutritionStatus,
      Clock clock) {
    this.dataSource = dataSource;
    this.aiStatus = aiStatus;
    this.nutritionStatus = nutritionStatus;
    this.clock = clock;
  }

  /**
   * Build a point-in-time status snapshot. Never throws — a failed DB probe is reported, not
   * raised.
   */
  public AdminStatusDto currentStatus() {
    boolean dbConnected = isDatabaseReachable();
    Instant lastAiCallAt = aiStatus.lastAiCallAt().orElse(null);
    Instant lastUsdaCallAt = nutritionStatus.lastUsdaCallAt().orElse(null);
    BigDecimal monthToDatePence = aiStatus.monthToDatePence();
    return new AdminStatusDto(
        dbConnected ? "UP" : "DEGRADED",
        Instant.now(clock),
        dbConnected,
        lastAiCallAt,
        lastUsdaCallAt,
        monthToDatePence);
  }

  private boolean isDatabaseReachable() {
    try (Connection connection = dataSource.getConnection()) {
      return connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS);
    } catch (Exception e) {
      // The status endpoint must stay UP-serving even when the DB is down — report DEGRADED.
      log.warn("admin/status DB connectivity probe failed: {}", e.toString());
      return false;
    }
  }
}
