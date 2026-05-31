package com.example.mealprep.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.example.mealprep.core.api.markers.BoundedCollection;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
              "com.example.mealprep.core.origin..",
              // core-1: TraceIdFilter (a OncePerRequestFilter seeding the per-request trace id into
              // MDC) is the same flavour of cross-cutting HTTP-layer concern as OriginFilter and,
              // per
              // lld/core.md §Package Layout, lives in core.audit.trace rather than a per-module
              // .api.
              // A servlet filter legitimately depends on the Servlet API; the trace-reading helper
              // (TraceContext) carries no Spring Web dependency and lives in
              // core.audit.domain.service.internal. Sanctioned carve-out, same pattern as above.
              "com.example.mealprep.core.audit.trace..",
              // E2E test-support (e2e-profile only): the stub/seed controllers
              // (E2eAiStubController, E2ePreferenceSeedController, E2eFeedbackSeedController) are
              // HTTP scaffolding for the black-box E2E harness — NOT product API surface — so they
              // live in `<module>.testing` (the same convention as the TestAiService double) rather
              // than `.api`, and legitimately depend on Spring Web. They are `@Profile("e2e")` and
              // never load in prod/dev/test. Sanctioned carve-out, same pattern as the exceptions
              // above; forward-compatible with later domains' e2e seeders under `<module>.testing`.
              "..testing..")
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

  /**
   * xcut-8: cross-cutting <b>{@code .internal} boundary</b> — no class residing OUTSIDE module
   * {@code X} may depend on any class in {@code com.example.mealprep.X..internal..}. This makes the
   * internal-package boundary as enforced as the repository boundary already is, and — unlike the
   * per-module {@code *BoundaryTest} rules — it is generic: a new module's {@code .internal}
   * sub-packages are protected automatically without editing this file.
   *
   * <p>"Module" is the first package segment after {@code com.example.mealprep.} (e.g. {@code
   * core}, {@code grocery}). An {@code .internal} segment anywhere below that (e.g. {@code
   * core.origin.internal}, {@code grocery.domain.service.internal}, {@code recipe.spi.internal}) is
   * module-private; only same-module classes may reach into it. Cross-module callers route through
   * the module's public SPI / service interfaces (which live OUTSIDE {@code .internal}).
   *
   * <p><b>Sanctioned carve-out (auth → core.origin.internal).</b> {@code
   * auth.config.AuthSecurityConfig} wires the {@code OriginFilter} bean (it must be added onto the
   * {@code HttpSecurity} chain in the same {@code @Configuration} that defines the chain — see the
   * {@code core.origin} carve-out note on {@code springWebStaysInApi} above) and therefore injects
   * the {@code core.origin.internal.InMemoryTokenBucketRateLimiter} {@code @Component} as a
   * constructor argument to that bean factory method. This is the single established cross-module
   * {@code .internal} dependency in v1; it is narrow (one class, one field, the security-chain
   * wiring seam) and intentional. All other cross-module {@code .internal} access is forbidden.
   */
  @ArchTest
  static final ArchRule noCrossModuleInternalAccess =
      classes()
          .that()
          .resideInAPackage("com.example.mealprep..")
          .should(notDependOnAnotherModulesInternalPackage())
          .as(
              "no class outside module X may depend on com.example.mealprep.X..internal.. —"
                  + " cross-module callers route through the module's public SPI / service"
                  + " interfaces (one sanctioned carve-out: auth.config.AuthSecurityConfig may"
                  + " inject core.origin.internal.InMemoryTokenBucketRateLimiter to wire the"
                  + " OriginFilter bean onto the security chain).")
          .allowEmptyShould(true);

  /**
   * Matches {@code com.example.mealprep.<module>...internal[.<...>].<Type>} — captures the module
   * (group 1) of any class that lives in (or under) an {@code internal} package segment. The {@code
   * \.internal(?:\.|$)} guard means a class literally in {@code ...internal} or any sub-package of
   * it matches, while an unrelated package merely containing the substring "internal" in a longer
   * word would not (the segment is bounded by dots).
   */
  private static final Pattern INTERNAL_FQN =
      Pattern.compile("^com\\.example\\.mealprep\\.([^.]+)\\..*\\.internal(?:\\.|$).*");

  /**
   * Sanctioned cross-module {@code .internal} dependency — see {@link
   * #noCrossModuleInternalAccess}.
   */
  private static boolean isSanctionedCarveOut(String originClass, String targetClass) {
    return "com.example.mealprep.auth.config.AuthSecurityConfig".equals(originClass)
        && "com.example.mealprep.core.origin.internal.InMemoryTokenBucketRateLimiter"
            .equals(targetClass);
  }

  /** Module = first package segment after {@code com.example.mealprep.}, else null. */
  private static String moduleOf(String fullyQualifiedName) {
    if (!fullyQualifiedName.startsWith("com.example.mealprep.")) {
      return null;
    }
    String rest = fullyQualifiedName.substring("com.example.mealprep.".length());
    int dot = rest.indexOf('.');
    return dot < 0 ? null : rest.substring(0, dot);
  }

  private static ArchCondition<JavaClass> notDependOnAnotherModulesInternalPackage() {
    return new ArchCondition<>("not depend on another module's .internal package") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        String originName = origin.getFullName();
        String originModule = moduleOf(originName);
        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
          JavaClass target = dependency.getTargetClass();
          String targetName = target.getFullName();
          Matcher m = INTERNAL_FQN.matcher(targetName);
          if (!m.matches()) {
            continue; // target is not in any module's .internal package
          }
          String targetModule = m.group(1);
          if (targetModule.equals(originModule)) {
            continue; // same-module access into its own .internal is fine
          }
          if (isSanctionedCarveOut(originName, targetName)) {
            continue; // narrow documented carve-out
          }
          events.add(
              SimpleConditionEvent.violated(
                  origin,
                  String.format(
                      "%s (module '%s') depends on %s in module '%s' .internal package — %s",
                      originName,
                      originModule,
                      targetName,
                      targetModule,
                      dependency.getDescription())));
        }
      }
    };
  }

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
