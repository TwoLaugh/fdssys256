package com.example.mealprep.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.CrossOrigin;

/**
 * Cross-module / cross-cutting architectural rules. Per-module repository-isolation rules live in
 * {@code <module>/<Module>BoundaryTest.java} so that adding a new module doesn't require editing a
 * shared file.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ModuleBoundaryTest {

  @ArchTest
  static final ArchRule springWebStaysInApi =
      noClasses()
          .that()
          .resideOutsideOfPackages(
              "..api..", "..config..", "com.example.mealprep.ai.domain.service.internal..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
          .as("Spring Web / Servlet types are an HTTP-layer concern; keep them in `<module>.api`.")
          .allowEmptyShould(true);

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

  /**
   * CORS is a cross-cutting concern handled centrally in {@code core.config.DevCorsConfiguration}
   * (dev-profile only). Sprinkling {@code @CrossOrigin} on controllers is "less preferred" per the
   * roadmap; this rule turns that prescription into an automated guard.
   */
  @ArchTest
  static final ArchRule crossOriginOnlyInCoreConfig =
      noMethods()
          .that()
          .areDeclaredInClassesThat()
          .resideOutsideOfPackage("com.example.mealprep.core.config..")
          .should()
          .beAnnotatedWith(CrossOrigin.class)
          .as(
              "CORS lives in `core.config` (DevCorsConfiguration); no controller method may"
                  + " annotate with @CrossOrigin.")
          .allowEmptyShould(true);
}
