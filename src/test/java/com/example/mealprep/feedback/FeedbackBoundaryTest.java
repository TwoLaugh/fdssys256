package com.example.mealprep.feedback;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Feedback-module architectural boundary. Two rules:
 *
 * <ul>
 *   <li>No code outside {@code feedback..} may depend on {@code feedback.domain.repository..} — the
 *       actual cross-module enforcement. Repos themselves are {@code public} so {@code
 *       FeedbackServiceImpl} (which lives in {@code feedback.domain.service} per the LLD style
 *       guide) can inject them; the SAME-module access is allowed, only OUT-of-module is blocked.
 *   <li>Anything inside {@code feedback.spi..} <i>is</i> public — SPIs are the intended
 *       cross-module surface.
 * </ul>
 *
 * <p>The 01a-shipped "repos must be package-private" rule was relaxed in 01b — package-private
 * across packages is mutually exclusive with locating the impl in {@code domain.service} (per the
 * LLD style guide §Module Package Structure). The cross-module-import rule still enforces the
 * boundary the original rule was defending.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class FeedbackBoundaryTest {

  @ArchTest
  static final ArchRule noCrossModuleRepoImport =
      noClasses()
          .that()
          .resideOutsideOfPackages("com.example.mealprep.feedback..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.feedback.domain.repository..")
          .as(
              "feedback repos are accessible only within the feedback module —"
                  + " cross-module callers go through FeedbackQueryService /"
                  + " FeedbackUpdateService.")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule spiAllowed =
      classes()
          .that()
          .resideInAPackage("com.example.mealprep.feedback.spi..")
          .should()
          .bePublic()
          .as("feedback SPIs are intended to be cross-module visible")
          .allowEmptyShould(true);
}
