package com.example.mealprep.feedback;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Feedback-module architectural boundary. Three rules:
 *
 * <ul>
 *   <li>Repos under {@code feedback.domain.repository..} are package-private (not {@code public}).
 *   <li>No code outside {@code feedback..} may depend on {@code feedback.domain.repository..}.
 *   <li>Anything inside {@code feedback.spi..} <i>is</i> public — SPIs are the intended
 *       cross-module surface.
 * </ul>
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class FeedbackBoundaryTest {

  @ArchTest
  static final ArchRule reposPackagePrivate =
      classes()
          .that()
          .resideInAPackage("..feedback.domain.repository..")
          .should()
          .notBePublic()
          .as(
              "feedback repositories must be package-private — cross-module access goes through services")
          .allowEmptyShould(true);

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
