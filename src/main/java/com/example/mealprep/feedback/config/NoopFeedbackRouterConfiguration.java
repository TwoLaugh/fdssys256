package com.example.mealprep.feedback.config;

import com.example.mealprep.feedback.domain.entity.RoutingFailureKind;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.service.internal.ConfidenceGate;
import com.example.mealprep.feedback.domain.service.internal.FeedbackRouter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the default {@link FeedbackRouter} when no other implementation is on the classpath.
 * Follows the SPI-with-Noop pattern: {@code @Configuration + @Bean + @ConditionalOnMissingBean}
 * (NOT {@code @Component @ConditionalOnMissingBean} — the latter has order-of-evaluation pitfalls
 * per the round-5 retro).
 *
 * <p>The bean method name {@code defaultFeedbackRouter} differs from the enclosing class name to
 * avoid the {@code BeanDefinitionOverrideException} round-5 lesson.
 *
 * <p>Feedback-01d ships the real {@link FeedbackRouter} (as a {@code @Bean} in its own
 * {@code @Configuration}) which then wins the {@code @ConditionalOnMissingBean} check.
 */
@Configuration
public class NoopFeedbackRouterConfiguration {

  @Bean
  @ConditionalOnMissingBean(FeedbackRouter.class)
  public FeedbackRouter defaultFeedbackRouter() {
    return new NoopFeedbackRouter();
  }

  /**
   * Default routing impl when feedback-01d's real router is absent. Logs WARN per classification
   * and leaves the entry at {@code CLASSIFIED} with zero routes.
   */
  public static final class NoopFeedbackRouter implements FeedbackRouter {

    private static final Logger log = LoggerFactory.getLogger(NoopFeedbackRouter.class);

    @Override
    public void routeAll(
        UUID feedbackId, List<ConfidenceGate.ScoredClassification> classifications) {
      log.warn(
          "NoopFeedbackRouter invoked for feedbackId={} with {} classifications;"
              + " feedback-01d's real router is not on the classpath — entry stays at CLASSIFIED.",
          feedbackId,
          classifications == null ? 0 : classifications.size());
    }

    @Override
    public RouteReplayResult routeOneForReplay(
        UUID feedbackId, ConfidenceGate.ScoredClassification scored) {
      log.warn(
          "NoopFeedbackRouter.routeOneForReplay invoked for feedbackId={};"
              + " feedback-01d's real router is not on the classpath — replay is a no-op.",
          feedbackId);
      return new RouteReplayResult(
          UUID.randomUUID(), RoutingStatus.FAILED, RoutingFailureKind.UNKNOWN);
    }
  }
}
