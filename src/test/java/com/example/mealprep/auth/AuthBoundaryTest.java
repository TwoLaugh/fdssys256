package com.example.mealprep.auth;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Auth-module repository isolation. Other modules must not import {@code auth.domain.repository}.
 * Cross-module callers go through {@code AuthQueryService} / {@code CurrentUserResolver}.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class AuthBoundaryTest {

  @ArchTest
  static final ArchRule authReposAreInternalToAuth =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.auth..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.auth.domain.repository..")
          .as(
              "auth repos are accessible only within the auth module —"
                  + " cross-module callers go through AuthQueryService / CurrentUserResolver.")
          .allowEmptyShould(true);
}
