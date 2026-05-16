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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the real {@link FeedbackRouter} as a {@code @Bean}, so 01c's {@link
 * NoopFeedbackRouterConfiguration#defaultFeedbackRouter()} defers via
 * {@code @ConditionalOnMissingBean(FeedbackRouter.class)}.
 *
 * <p>Style note (per agent template §SPI-with-Noop and the round-5 retro): the
 * {@code @Configuration + @Bean} recipe — NOT {@code @Component} on the impl — is what preserves
 * the conditional-bean ordering across modules.
 */
@Configuration
public class FeedbackRouterConfiguration {

  @Bean
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
