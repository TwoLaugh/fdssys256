package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.AiOperationalStatusService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@code AiOperationalStatusServiceImpl} (admin/status SPI, C-G-032): last-call
 * passthrough and the calendar-month-to-date cutoff + micropence→pence conversion.
 */
@ExtendWith(MockitoExtension.class)
class AiOperationalStatusServiceImplTest {

  @Mock private AiCallLogRepository repository;

  // Mid-month: month-to-date cutoff must be 2026-05-01T00:00:00Z (start of May, UTC).
  private final Instant now = Instant.parse("2026-05-17T09:30:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  private AiOperationalStatusService newService() throws ReflectiveOperationException {
    Class<?> impl =
        Class.forName(
            "com.example.mealprep.ai.domain.service.internal.AiOperationalStatusServiceImpl");
    var ctor = impl.getDeclaredConstructor(AiCallLogRepository.class, Clock.class);
    ctor.setAccessible(true);
    return (AiOperationalStatusService) ctor.newInstance(repository, clock);
  }

  @Test
  void lastAiCallAt_passesThroughMaxCreatedAt() throws Exception {
    Instant last = Instant.parse("2026-05-17T09:29:00Z");
    when(repository.findMaxCreatedAt()).thenReturn(last);

    assertThat(newService().lastAiCallAt()).contains(last);
  }

  @Test
  void lastAiCallAt_emptyWhenNoRows() throws Exception {
    when(repository.findMaxCreatedAt()).thenReturn(null);

    assertThat(newService().lastAiCallAt()).isEmpty();
  }

  @Test
  void monthToDatePence_usesStartOfCalendarMonthUtc_andConverts() throws Exception {
    // 12_345_000 micropence = 12.345 pence → HALF_UP 2dp = 12.35 (rounds .345 up at 2dp? .345→.35)
    when(repository.sumCostMicroPenceGlobalSince(any())).thenReturn(12_345_000L);

    BigDecimal result = newService().monthToDatePence();

    assertThat(result).isEqualByComparingTo(new BigDecimal("12.35"));
    ArgumentCaptor<Instant> sinceCap = ArgumentCaptor.forClass(Instant.class);
    verify(repository).sumCostMicroPenceGlobalSince(sinceCap.capture());
    assertThat(sinceCap.getValue()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
  }

  @Test
  void monthToDatePence_zeroWhenNoSpend() throws Exception {
    when(repository.sumCostMicroPenceGlobalSince(any())).thenReturn(0L);

    assertThat(newService().monthToDatePence()).isEqualByComparingTo(new BigDecimal("0.00"));
  }
}
