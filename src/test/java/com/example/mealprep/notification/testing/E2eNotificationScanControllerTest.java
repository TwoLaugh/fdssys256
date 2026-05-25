package com.example.mealprep.notification.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.notification.scanner.ExpiryWarningScanner;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit test for the e2e-only {@link E2eNotificationScanController}: it must delegate straight to
 * the same {@code scan()} the {@code @Scheduled} trigger drives and echo the fired-user count back,
 * with no extra orchestration of its own.
 */
@ExtendWith(MockitoExtension.class)
class E2eNotificationScanControllerTest {

  @Mock private ExpiryWarningScanner expiryWarningScanner;

  @Test
  void runExpiryScan_delegatesToScannerAndReturnsFiredCount() {
    when(expiryWarningScanner.scan()).thenReturn(3);
    E2eNotificationScanController controller =
        new E2eNotificationScanController(expiryWarningScanner);

    ResponseEntity<Map<String, Integer>> response = controller.runExpiryScan();

    verify(expiryWarningScanner).scan();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("firedForUsers", 3);
  }

  @Test
  void runExpiryScan_zeroFired_isReportedAsZero() {
    when(expiryWarningScanner.scan()).thenReturn(0);
    E2eNotificationScanController controller =
        new E2eNotificationScanController(expiryWarningScanner);

    ResponseEntity<Map<String, Integer>> response = controller.runExpiryScan();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("firedForUsers", 0);
  }
}
