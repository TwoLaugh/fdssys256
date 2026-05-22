package com.example.mealprep.notification.validation;

import com.example.mealprep.notification.api.dto.UpdateNotificationPreferenceRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Validator backing {@link ValidQuietHours}. Operates on the whole {@link
 * UpdateNotificationPreferenceRequest} so it can cross-check the enabled flag against the start/end
 * pair and validate the timezone in one pass.
 */
public class QuietHoursValidator
    implements ConstraintValidator<ValidQuietHours, UpdateNotificationPreferenceRequest> {

  @Override
  public boolean isValid(
      UpdateNotificationPreferenceRequest request, ConstraintValidatorContext context) {
    if (request == null) {
      return true; // @NotNull on the body handles the null-request case.
    }

    boolean valid = true;

    if (!isTimezoneValid(request.timezone())) {
      valid = addViolation(context, "timezone", "must be a valid IANA zone id");
    }

    LocalTime start = request.quietHoursStart();
    LocalTime end = request.quietHoursEnd();
    boolean enabled = request.quietHoursEnabled();
    boolean bothNull = start == null && end == null;
    boolean bothPresent = start != null && end != null;

    if (!bothNull && !bothPresent) {
      valid =
          addViolation(
              context,
              "quietHoursStart",
              "quietHoursStart and quietHoursEnd must both be set or both be null");
    } else if (enabled && bothNull) {
      valid =
          addViolation(
              context, "quietHoursEnabled", "quiet hours enabled but no start/end window provided");
    } else if (!enabled && bothPresent) {
      valid =
          addViolation(
              context,
              "quietHoursEnabled",
              "start/end window provided but quiet hours not enabled");
    } else if (bothPresent && Objects.equals(start, end)) {
      valid =
          addViolation(
              context, "quietHoursEnd", "start and end must differ (zero-length window rejected)");
    }

    return valid;
  }

  private static boolean isTimezoneValid(String timezone) {
    if (timezone == null || timezone.isBlank()) {
      return false;
    }
    try {
      ZoneId.of(timezone);
      return true;
    } catch (DateTimeException ex) {
      return false;
    }
  }

  private static boolean addViolation(
      ConstraintValidatorContext context, String field, String message) {
    context.disableDefaultConstraintViolation();
    context
        .buildConstraintViolationWithTemplate(message)
        .addPropertyNode(field)
        .addConstraintViolation();
    return false;
  }
}
