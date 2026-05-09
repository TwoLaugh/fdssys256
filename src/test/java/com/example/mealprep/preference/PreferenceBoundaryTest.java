package com.example.mealprep.preference;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Preference-module repository isolation. Other modules must not import {@code
 * preference.domain.repository}. Cross-module callers go through {@code PreferenceQueryService} /
 * {@code PreferenceUpdateService}.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class PreferenceBoundaryTest {

  @ArchTest
  static final ArchRule preferenceReposAreInternalToPreference =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.preference..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.preference.domain.repository..")
          .as(
              "preference repos are accessible only within the preference module —"
                  + " cross-module callers go through PreferenceQueryService /"
                  + " PreferenceUpdateService.")
          .allowEmptyShould(true);
}
