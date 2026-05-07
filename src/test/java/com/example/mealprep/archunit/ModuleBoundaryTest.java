package com.example.mealprep.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Module-boundary rules for the MealPrep AI codebase.
 *
 * <p>These rules encode the architectural intent from {@code lld/style-guide.md §Module Package
 * Structure}: each module owns an {@code api/} (HTTP-facing) and a {@code domain/} (business +
 * persistence) sub-package, and other modules may inject {@code domain.service.*} interfaces but
 * never reach into another module's {@code domain.repository}. Spring Web concerns stay in {@code
 * api/}; JPA repository imports stay in {@code domain.repository/}.
 *
 * <p>The rules are no-ops on the empty bootstrap codebase but compile and execute, so subsequent
 * tickets land into a working ArchUnit gate. Adding a deliberately-broken cross-module import
 * causes one of these tests to fail.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ModuleBoundaryTest {

  /** Spring Web should not appear outside of {@code api/} packages. */
  @ArchTest
  static final ArchRule springWebStaysInApi =
      noClasses()
          .that()
          .resideOutsideOfPackages("..api..", "..config..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
          .as("Spring Web / Servlet types are an HTTP-layer concern; keep them in `<module>.api`.")
          .allowEmptyShould(true);

  /** JpaRepository should not appear outside of {@code domain.repository/} packages. */
  @ArchTest
  static final ArchRule jpaRepositoriesStayInDomainRepository =
      noClasses()
          .that()
          .resideOutsideOfPackage("..domain.repository..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.data.jpa.repository.JpaRepository")
          .as("Repositories live in `<module>.domain.repository`; nothing else may import them.")
          .allowEmptyShould(true);

  /** No module may import another module's {@code domain.repository} sub-package. */
  @ArchTest
  static final ArchRule noCrossModuleRepositoryImports =
      noClasses()
          .that()
          .resideInAPackage("com.example.mealprep.(*)..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.(*).domain.repository..")
          .as(
              "Cross-module access goes through the target module's `<Module>QueryService` /"
                  + " `<Module>UpdateService`, never through its repositories.")
          .allowEmptyShould(true);

  /**
   * Entities must live in {@code domain.entity}, never leak into {@code api/} — except the {@code
   * api.mapper} sub-package, which exists precisely to bridge entities and DTOs. Controllers and
   * DTOs themselves must not depend on entities.
   */
  @ArchTest
  static final ArchRule entitiesStayInDomain =
      noClasses()
          .that()
          .resideInAPackage("..api..")
          .and()
          .resideOutsideOfPackage("..api.mapper..")
          .should()
          .dependOnClassesThat()
          .areAnnotatedWith(jakarta.persistence.Entity.class)
          .as("Entities are an internal concern; controllers and DTOs must not depend on them.")
          .allowEmptyShould(true);
}
