package com.example.mealprep.feedback.config;

import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.domain.service.internal.DestinationDispatcherRegistry;
import com.example.mealprep.feedback.domain.service.internal.FeedbackRouter;
import com.example.mealprep.feedback.domain.service.internal.FeedbackRouterImpl;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the real {@link FeedbackRouter} as a {@code @Bean}, so 01c's {@link
 * NoopFeedbackRouterConfiguration#defaultFeedbackRouter()} defers via
 * {@code @ConditionalOnMissingBean(FeedbackRouter.class)}.
 *
 * <p>Style note (per agent template §SPI-with-Noop and the round-5 retro): the
 * {@code @Configuration + @Bean} recipe — NOT {@code @Component} on the impl — is what preserves
 * the conditional-bean ordering across modules.
 *
 * <p><b>{@code @Primary} (correctness, not style).</b> {@code @ConditionalOnMissingBean} is only
 * order-reliable for <em>auto-configuration</em> classes (evaluated after all user
 * {@code @Configuration}); between two peer user {@code @Configuration} classes the bean-definition
 * processing order is non-deterministic. On an unlucky component-scan order the Noop's condition is
 * evaluated before this real bean is registered, so BOTH register and a single-point injection
 * (e.g. {@code CorrectionReplayer}) fails with "expected single matching bean but found 2:
 * defaultFeedbackRouter,feedbackRouter" → APPLICATION FAILED TO START (an intermittent boot flake).
 * Marking the real router {@code @Primary} makes by-type injection resolve to it regardless of scan
 * order; when the conditional correctly suppresses the Noop, the lone bean is unaffected.
 */
@Configuration
public class FeedbackRouterConfiguration {

  @Bean
  @Primary
  public FeedbackRouter feedbackRouter(
      FeedbackEntryRepository entryRepository,
      RoutingLogRepository routingLogRepository,
      DestinationDispatcherRegistry registry,
      ApplicationEventPublisher eventPublisher,
      @Qualifier(FeedbackTxTemplateConfig.REQUIRES_NEW_TX_TEMPLATE)
          TransactionTemplate requiresNewTxTemplate,
      Clock clock) {
    return new FeedbackRouterImpl(
        entryRepository,
        routingLogRepository,
        registry,
        eventPublisher,
        requiresNewTxTemplate,
        clock);
  }
}
