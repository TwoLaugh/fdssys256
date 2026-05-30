package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.service.internal.PasswordStrengthValidator;
import com.example.mealprep.auth.validation.ValidPasswordValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link ValidPasswordValidator}. Uses a real {@link PasswordStrengthValidator}
 * (in-module collaborator — never mocked per playbook) wired with a real {@link AuthProperties}.
 * The {@link ConstraintValidatorContext} is a cross-cutting framework type so it is mocked. Pins:
 * null rejected without touching the strength validator/context; valid password short-circuits
 * true; an invalid password disables the default violation and registers the first reason's {@code
 * name()} as the template — killing the null-branch, the {@code reasons.isEmpty()} branch and the
 * {@code reasons.get(0)} index mutants.
 */
class ValidPasswordValidatorTest {

  private final AuthProperties properties =
      new AuthProperties(null, null, null, null, null, 12, 128, null, null, null, null);
  private final PasswordStrengthValidator strength = new PasswordStrengthValidator(properties);
  private final ValidPasswordValidator validator = new ValidPasswordValidator(strength);

  private ConstraintValidatorContext context;
  private ConstraintViolationBuilder builder;

  @BeforeEach
  void setUp() {
    context = mock(ConstraintValidatorContext.class);
    builder = mock(ConstraintViolationBuilder.class);
    when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(builder);
  }

  @Test
  void nullPassword_isInvalid_andContextUntouched() {
    assertThat(validator.isValid(null, context)).isFalse();
    verifyNoInteractions(context);
  }

  @Test
  void strongPassword_isValid_andNoViolationBuilt() {
    assertThat(validator.isValid("correct-horse-battery-staple", context)).isTrue();
    verify(context, never()).disableDefaultConstraintViolation();
    verify(context, never()).buildConstraintViolationWithTemplate(anyString());
  }

  @Test
  void tooShortPassword_isInvalid_andReportsTOO_SHORT_asTemplate() {
    assertThat(validator.isValid("short", context)).isFalse();
    verify(context).disableDefaultConstraintViolation();
    ArgumentCaptor<String> tmpl = ArgumentCaptor.forClass(String.class);
    verify(context).buildConstraintViolationWithTemplate(tmpl.capture());
    assertThat(tmpl.getValue()).isEqualTo("TOO_SHORT");
    verify(builder).addConstraintViolation();
  }

  @Test
  void emptyPassword_isInvalid_andStillReportsTOO_SHORT() {
    // Empty (not null) must still flow through the strength validator, not the null branch.
    assertThat(validator.isValid("", context)).isFalse();
    verify(context).buildConstraintViolationWithTemplate("TOO_SHORT");
  }
}
