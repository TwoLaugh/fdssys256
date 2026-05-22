package com.example.mealprep.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.example.mealprep.core.api.markers.BoundedCollection;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Collection;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.filter.OncePerRequestFilter;

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
              "..api..",
              "..config..",
              "com.example.mealprep.ai.domain.service.internal..",
              // recipe-02a: the RecipeImageStore SPI (and its v1 local-FS implementation +
              // image-write service) deliberately exposes Spring's MultipartFile / MediaType /
              // Resource — these are the natural lingua franca for storage SPIs and an
              // application-specific re-wrapping buys nothing. Conventional carve-out, same
              // pattern as the ai.domain.service.internal exception above.
              "com.example.mealprep.recipe.spi..",
              "com.example.mealprep.recipe.domain.service.internal..",
              // core-02b: the origin-tracking foundation is itself an HTTP-layer concern that, per
              // lld/core.md, lives in core.origin (cross-cutting) rather than a per-module .api.
              // OriginFilter (a OncePerRequestFilter), the @RequestScope OriginContext, the servlet
              // request/response handling, and the HandlerExceptionResolver delegation that routes
              // filter-thrown rejections back through @ExceptionHandler all legitimately depend on
              // Spring Web / Servlet types. Same sanctioned carve-out as the SPI exceptions above.
              "com.example.mealprep.core.origin..")
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

  /**
   * New controller endpoints returning a raw {@code List<*Dto>} / {@code Collection<*Dto>} from a
   * {@code @GetMapping} must be annotated {@code @BoundedCollection} with a justification, or
   * paginated as {@code Page<>}. Catches drift where a new "list everything" endpoint slips in
   * without considering pagination.
   *
   * <p>Per ticket {@code infra/01b-list-endpoint-pagination-audit}.
   */
  @ArchTest
  static final ArchRule listReturningGetMappingsMustBeAnnotatedBoundedCollection =
      methods()
          .that()
          .areAnnotatedWith(GetMapping.class)
          .and()
          .haveRawReturnType(returnsRawListOfDto())
          .should()
          .beAnnotatedWith(BoundedCollection.class)
          .as(
              "Controller @GetMapping methods returning List<*Dto> / Collection<*Dto> must be"
                  + " annotated @BoundedCollection (justifying why the collection is bounded by"
                  + " domain semantics). Otherwise use Page<*Dto> + Pageable.")
          .allowEmptyShould(true);

  /**
   * Core-02b: only the {@link com.example.mealprep.core.origin.OriginFilter} reads the {@code
   * X-Origin} family of headers. If anyone else writes their own {@link OncePerRequestFilter} that
   * inspects {@code X-Origin}, the centralised confidence-floor / depth-check / rate-limit /
   * annotation-check policy splinters across the codebase — and the next refactor of any of those
   * policies would silently miss the duplicate. Production-only scope (tests are excluded by the
   * class-level {@code ImportOption.DoNotIncludeTests}).
   */
  @ArchTest
  static final ArchRule onlyOriginFilterReadsOriginHeaders =
      noClasses()
          .that()
          .areAssignableTo(OncePerRequestFilter.class)
          .and()
          .resideOutsideOfPackage("com.example.mealprep.core.origin..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.example.mealprep.core.api.OriginHeaders")
          .as(
              "Only OriginFilter (in core.origin) may read X-Origin* headers via OriginHeaders."
                  + " Other filters must not duplicate the origin policy.")
          .allowEmptyShould(true);

  /**
   * Belt-and-braces companion to {@link #onlyOriginFilterReadsOriginHeaders}: if a future
   * contributor types the literal {@code "X-Origin"} into a non-{@code core.origin} filter class
   * (sidestepping the constants), this rule still catches it. ArchUnit cannot match arbitrary
   * string literals, but it can detect classes that reference our {@link
   * com.example.mealprep.core.api.OriginHeaders} constants — the supplementary {@code grep
   * '"X-Origin"' src/} acceptance check covers the literal-string escape hatch (per the ticket
   * edge-case list).
   */
  @ArchTest
  static final ArchRule noFiltersOutsideCoreOriginUseOriginContext =
      noFields()
          .that()
          .areDeclaredInClassesThat()
          .areAssignableTo(OncePerRequestFilter.class)
          .and()
          .areDeclaredInClassesThat()
          .resideOutsideOfPackage("com.example.mealprep.core.origin..")
          .should()
          .haveRawType("com.example.mealprep.core.origin.OriginContext")
          .as(
              "OriginContext is populated only by OriginFilter; other filters must not hold a"
                  + " field of that type.")
          .allowEmptyShould(true);

  /**
   * Notification-01a: the notification module's repositories are module-private — cross-module
   * callers route through {@code NotificationQueryService} / {@code NotificationUpdateService}.
   * Per-module isolation also lives in {@code notification.NotificationBoundaryTest}; this entry
   * keeps the cross-cutting suite's coverage explicit for the newest module per the ticket.
   */
  @ArchTest
  static final ArchRule notificationReposAreModulePrivate =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.notification..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.notification.domain.repository..")
          .as(
              "notification repos are accessible only within the notification module —"
                  + " cross-module callers go through the notification service interfaces.")
          .allowEmptyShould(true);

  private static DescribedPredicate<JavaClass> returnsRawListOfDto() {
    return new DescribedPredicate<>("a raw List/Collection (assignable to java.util.Collection)") {
      @Override
      public boolean test(JavaClass returnType) {
        if (returnType == null) {
          return false;
        }
        return returnType.isAssignableTo(Collection.class) || returnType.isAssignableTo(List.class);
      }
    };
  }

  /**
   * Recipe-02a: the {@code RecipeImageStore} SPI lives in {@code recipe.spi..} and is public to the
   * recipe module; its {@code LocalFilesystemImageStore} v1 implementation lives in {@code
   * recipe.spi.internal..} and must not leak outside that sub-package. Cross-module callers go
   * through {@code RecipeQueryService.getById(...).imageUrl()} (read) or the HTTP serve endpoint
   * (read) — never the store directly.
   */
  @ArchTest
  static final ArchRule localFilesystemImageStoreIsInternalOnly =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.recipe.spi.internal..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName(
              "com.example.mealprep.recipe.spi.internal.LocalFilesystemImageStore")
          .as(
              "LocalFilesystemImageStore is an internal implementation of RecipeImageStore;"
                  + " callers inject the RecipeImageStore SPI, not the v1 concrete class.")
          .allowEmptyShould(true);
}
