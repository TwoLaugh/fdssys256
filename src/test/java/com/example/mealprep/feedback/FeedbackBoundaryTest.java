package com.example.mealprep.feedback;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.example.mealprep.feedback.bridge.internal.FeedbackBridgeSupport;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Component;

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

  /**
   * feedback-01g §24: every concrete class in {@code feedback.bridge..} must be a
   * {@code @Component} and/or extend {@link FeedbackBridgeSupport}. Prevents drift toward rogue
   * bridge patterns. The abstract {@link FeedbackBridgeSupport} base itself is exempt (it is not a
   * registrable bean), as is the {@code feedback.bridge.internal} infrastructure that is wired
   * explicitly (the cleanup scheduler is a {@code @Component}; the support base is abstract).
   */
  @ArchTest
  static final ArchRule bridgesAreComponentsOrExtendSupport =
      classes()
          .that()
          .resideInAPackage("com.example.mealprep.feedback.bridge..")
          .and()
          .doNotHaveModifier(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT)
          .should()
          .beAnnotatedWith(Component.class)
          .orShould()
          .beAssignableTo(FeedbackBridgeSupport.class)
          .as(
              "feedback.bridge classes must be @Component beans or extend FeedbackBridgeSupport"
                  + " (no rogue bridge patterns).")
          .allowEmptyShould(true);
}
