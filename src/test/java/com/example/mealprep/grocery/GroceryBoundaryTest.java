package com.example.mealprep.grocery;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Grocery-module isolation rules. Per ticket-01a §ArchUnit and lld/grocery.md §Package Layout. Four
 * rules ship in 01a:
 *
 * <ul>
 *   <li>Repositories in {@code domain.repository..} are package-private (Java visibility is the
 *       first fence; this is the ArchUnit backstop).
 *   <li>Other modules must not reach into {@code grocery.domain.repository..}.
 *   <li>{@code *ServiceImpl} classes live only in {@code domain.service.internal..}.
 *   <li>{@code GroceryProvider} SPI implementations ({@code *GroceryProvider}) live only in the
 *       {@code domain.service.internal.providers..} pocket — vacuously true in 01a (no impls yet);
 *       {@code FakeGroceryProvider} (01e) and the deferred {@code TescoGroceryProvider} land there.
 *   <li>Spring-Web / Servlet types stay in {@code grocery.api..} / {@code grocery.config..} (the
 *       project-wide {@code springWebStaysInApi} rule, scoped to grocery here).
 * </ul>
 */
@AnalyzeClasses(
    packages = "com.example.mealprep.grocery",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class GroceryBoundaryTest {

  /**
   * Repositories are package-private — cross-package reach-through is impossible at the JVM level.
   */
  @ArchTest
  static final ArchRule reposArePackagePrivate =
      classes()
          .that()
          .resideInAPackage("com.example.mealprep.grocery.domain.repository..")
          .should()
          .notHaveModifier(JavaModifier.PUBLIC)
          .as(
              "grocery repositories are package-private — cross-module callers go through the"
                  + " grocery service interfaces, never the repository directly.")
          .allowEmptyShould(true);

  /** Other modules must not import {@code grocery.domain.repository..} (cross-cutting backstop). */
  @ArchTest
  static final ArchRule reposAreInternalToGrocery =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.grocery..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.grocery.domain.repository..")
          .as(
              "grocery repos are accessible only within the grocery module —"
                  + " cross-module callers go through the four grocery service interfaces.")
          .allowEmptyShould(true);

  /** Service implementations live in the internal plumbing package, not the public contract one. */
  @ArchTest
  static final ArchRule implsLiveInInternal =
      classes()
          .that()
          .haveSimpleNameEndingWith("ServiceImpl")
          .and()
          .resideInAPackage("com.example.mealprep.grocery..")
          .should()
          .resideInAPackage("com.example.mealprep.grocery.domain.service.internal..")
          .as("grocery *ServiceImpl classes live only in domain.service.internal..")
          .allowEmptyShould(true);

  /**
   * {@code GroceryProvider} SPI implementations live exclusively in the {@code
   * domain.service.internal.providers..} pocket — mirrors discovery's "impls in the source pocket"
   * pattern. Written against the {@code *GroceryProvider} naming so it does not depend on the SPI
   * interface (which ships in 01e); vacuously true in 01a.
   */
  @ArchTest
  static final ArchRule providerImplsLiveInProvidersPocket =
      classes()
          .that()
          .haveSimpleNameEndingWith("GroceryProvider")
          .and()
          .resideInAPackage("com.example.mealprep.grocery..")
          .should()
          .resideInAPackage("com.example.mealprep.grocery.domain.service.internal.providers..")
          .as(
              "GroceryProvider implementations live only in"
                  + " grocery.domain.service.internal.providers.. (the SPI pocket).")
          .allowEmptyShould(true);

  /**
   * Spring-Web / Servlet types are an HTTP-layer concern — keep them in grocery.api /
   * grocery.config.
   */
  @ArchTest
  static final ArchRule springWebStaysInApi =
      noClasses()
          .that()
          .resideInAPackage("com.example.mealprep.grocery..")
          .and()
          .resideOutsideOfPackages(
              "com.example.mealprep.grocery.api..", "com.example.mealprep.grocery.config..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
          .as(
              "Spring Web / Servlet types are an HTTP-layer concern; keep them in grocery.api /"
                  + " grocery.config.")
          .allowEmptyShould(true);
}
