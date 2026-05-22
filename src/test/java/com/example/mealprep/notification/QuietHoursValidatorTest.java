package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.api.dto.UpdateNotificationPreferenceRequest;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.testdata.NotificationTestData;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class QuietHoursValidatorTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private static UpdateNotificationPreferenceRequest request(
      boolean enabled, LocalTime start, LocalTime end, String tz) {
    Map<NotificationKind, Boolean> kinds = NotificationTestData.allEnabled();
    return new UpdateNotificationPreferenceRequest(kinds, enabled, start, end, tz, 30, 0L);
  }

  @Test
  void valid_disabledWithNullWindow() {
    var violations = validator.validate(request(false, null, null, "Europe/London"));
    assertThat(violations).isEmpty();
  }

  @Test
  void valid_enabledWithWrapAroundWindow() {
    var violations =
        validator.validate(request(true, LocalTime.of(22, 0), LocalTime.of(6, 0), "Europe/London"));
    assertThat(violations).isEmpty();
  }

  @Test
  void invalid_enabledButNullWindow() {
    var violations = validator.validate(request(true, null, null, "Europe/London"));
    assertThat(violations).isNotEmpty();
  }

  @Test
  void invalid_zeroLengthWindow() {
    var violations =
        validator.validate(
            request(true, LocalTime.of(22, 0), LocalTime.of(22, 0), "Europe/London"));
    assertThat(violations).isNotEmpty();
  }

  @Test
  void invalid_unknownTimezone() {
    var violations =
        validator.validate(request(true, LocalTime.of(22, 0), LocalTime.of(6, 0), "Mars/Phobos"));
    assertThat(violations).isNotEmpty();
  }

  @Test
  void invalid_onlyOneEndpointSet() {
    var violations = validator.validate(request(true, LocalTime.of(22, 0), null, "Europe/London"));
    assertThat(violations).isNotEmpty();
  }
}
