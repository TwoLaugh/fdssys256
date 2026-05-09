package com.example.mealprep.ai;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * AI-module repository isolation. Other modules must not import {@code ai.domain.repository}.
 * Cross-module callers go through {@code AiService}.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class AiBoundaryTest {

  @ArchTest
  static final ArchRule aiReposAreInternalToAi =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.ai..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.ai.domain.repository..")
          .as(
              "ai repos are accessible only within the ai module —"
                  + " cross-module callers go through AiService.")
          .allowEmptyShould(true);
}
