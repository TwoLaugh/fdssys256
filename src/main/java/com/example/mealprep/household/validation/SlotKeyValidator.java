package com.example.mealprep.household.validation;

import com.example.mealprep.household.domain.entity.SlotKind;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@link ValidSlotKey} implementation. {@code null} is left to {@code @NotNull}/{@code @NotBlank}
 * (returns valid here); a non-null key must match the kebab-case pattern, be within length, and not
 * collide (case-insensitively) with a built-in {@link SlotKind} name.
 */
public class SlotKeyValidator implements ConstraintValidator<ValidSlotKey, String> {

  private static final Pattern KEBAB_CASE = Pattern.compile("^[a-z0-9-]+$");

  /** Lower-cased built-in slot-kind names a custom key may not shadow. */
  private static final Set<String> RESERVED =
      Arrays.stream(SlotKind.values())
          .map(k -> k.name().toLowerCase(Locale.ROOT))
          .collect(Collectors.toUnmodifiableSet());

  @Override
  public boolean isValid(String value, ConstraintValidatorContext ctx) {
    if (value == null) {
      // Nullability is @NotNull/@NotBlank's job; the schema marks key required.
      return true;
    }
    if (value.isEmpty() || value.length() > ValidSlotKey.MAX_LENGTH) {
      return false;
    }
    if (!KEBAB_CASE.matcher(value).matches()) {
      return false;
    }
    return !RESERVED.contains(value.toLowerCase(Locale.ROOT));
  }
}
