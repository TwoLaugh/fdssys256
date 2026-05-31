package com.example.mealprep.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.service.AiOperationalStatusService;
import com.example.mealprep.nutrition.domain.service.NutritionOperationalStatusService;
import com.example.mealprep.ops.api.dto.AdminStatusDto;
import com.example.mealprep.ops.domain.service.AdminStatusService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@code AdminStatusService} (admin/status aggregator, C-G-032). */
@ExtendWith(MockitoExtension.class)
class AdminStatusServiceTest {

  @Mock private DataSource dataSource;
  @Mock private Connection connection;
  @Mock private AiOperationalStatusService aiStatus;
  @Mock private NutritionOperationalStatusService nutritionStatus;

  private final Instant now = Instant.parse("2026-05-31T12:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  private AdminStatusService service() {
    return new AdminStatusService(dataSource, aiStatus, nutritionStatus, clock);
  }

  @Test
  void currentStatus_UP_whenDbValid_andAggregatesSignals() throws Exception {
    Instant lastAi = Instant.parse("2026-05-31T11:59:00Z");
    Instant lastUsda = Instant.parse("2026-05-31T11:00:00Z");
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.isValid(2)).thenReturn(true);
    when(aiStatus.lastAiCallAt()).thenReturn(Optional.of(lastAi));
    when(aiStatus.monthToDatePence()).thenReturn(new BigDecimal("42.00"));
    when(nutritionStatus.lastUsdaCallAt()).thenReturn(Optional.of(lastUsda));

    AdminStatusDto dto = service().currentStatus();

    assertThat(dto.status()).isEqualTo("UP");
    assertThat(dto.dbConnected()).isTrue();
    assertThat(dto.checkedAt()).isEqualTo(now);
    assertThat(dto.lastAiCallAt()).isEqualTo(lastAi);
    assertThat(dto.lastUsdaCallAt()).isEqualTo(lastUsda);
    assertThat(dto.aiMonthToDatePence()).isEqualByComparingTo(new BigDecimal("42.00"));
  }

  @Test
  void currentStatus_DEGRADED_whenConnectionInvalid_andNullTimestampsTolerated() throws Exception {
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.isValid(2)).thenReturn(false);
    when(aiStatus.lastAiCallAt()).thenReturn(Optional.empty());
    when(aiStatus.monthToDatePence()).thenReturn(BigDecimal.ZERO);
    when(nutritionStatus.lastUsdaCallAt()).thenReturn(Optional.empty());

    AdminStatusDto dto = service().currentStatus();

    assertThat(dto.status()).isEqualTo("DEGRADED");
    assertThat(dto.dbConnected()).isFalse();
    assertThat(dto.lastAiCallAt()).isNull();
    assertThat(dto.lastUsdaCallAt()).isNull();
  }

  @Test
  void currentStatus_DEGRADED_whenGetConnectionThrows_neverPropagates() throws Exception {
    when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("db down"));
    when(aiStatus.lastAiCallAt()).thenReturn(Optional.empty());
    when(aiStatus.monthToDatePence()).thenReturn(BigDecimal.ZERO);
    when(nutritionStatus.lastUsdaCallAt()).thenReturn(Optional.empty());

    AdminStatusDto dto = service().currentStatus();

    assertThat(dto.status()).isEqualTo("DEGRADED");
    assertThat(dto.dbConnected()).isFalse();
  }
}
