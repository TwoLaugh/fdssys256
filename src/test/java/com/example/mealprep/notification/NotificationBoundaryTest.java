package com.example.mealprep.notification;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Notification-module architecture rules:
 *
 * <ul>
 *   <li>repositories are module-private — no cross-module dependency on {@code
 *       notification.domain.repository};
 *   <li>the {@code DeliveryChannel} SPI is public so sibling channel impls (push/email) may plug in
 *       cross-module — there is no rule forbidding cross-module use of the SPI, only the repos.
 * </ul>
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class NotificationBoundaryTest {

  @ArchTest
  static final ArchRule notificationReposAreInternalToNotification =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.notification..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.notification.domain.repository..")
          .as(
              "notification repos are accessible only within the notification module —"
                  + " cross-module callers go through NotificationQueryService /"
                  + " NotificationUpdateService.")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule notificationInternalServicesStayInternal =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.notification..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.notification.domain.service.internal")
          .as(
              "the notification dispatcher/resolver/debouncer are internal — cross-module callers"
                  + " use the public service interfaces. (The DeliveryChannel SPI lives in the"
                  + " .delivery sub-package and is intentionally public.)")
          .allowEmptyShould(true);
}
