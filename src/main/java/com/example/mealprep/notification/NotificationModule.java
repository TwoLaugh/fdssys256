package com.example.mealprep.notification;

import com.example.mealprep.notification.config.NotificationProperties;
import com.example.mealprep.notification.domain.service.NotificationQueryService;
import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import com.example.mealprep.notification.scanner.config.ScannerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Module facade — re-exports the notification module's public service interfaces. Cross-module
 * callers inject this (or an individual service) rather than reaching into {@code domain.service.*}
 * directly. Mirrors {@code AuthModule} / {@code PreferenceModule}; thin and carries no business
 * logic.
 *
 * <p>{@code @EnableConfigurationProperties(NotificationProperties.class)} binds the {@code
 * mealprep.notification.*} keys. The internal {@code NotificationDispatcher} is intentionally NOT
 * re-exported — it is package-private and listener-facing only.
 */
@Component
@EnableConfigurationProperties({NotificationProperties.class, ScannerProperties.class})
public class NotificationModule {

  private final NotificationQueryService notificationQueryService;
  private final NotificationUpdateService notificationUpdateService;

  public NotificationModule(
      NotificationQueryService notificationQueryService,
      NotificationUpdateService notificationUpdateService) {
    this.notificationQueryService = notificationQueryService;
    this.notificationUpdateService = notificationUpdateService;
  }

  public NotificationQueryService query() {
    return notificationQueryService;
  }

  public NotificationUpdateService update() {
    return notificationUpdateService;
  }
}
