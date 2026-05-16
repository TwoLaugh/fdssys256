package com.example.mealprep.feedback.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Provides a {@link TransactionTemplate} configured for {@code PROPAGATION_REQUIRES_NEW} so the
 * async classification listener can open short, independent transactions outside the AI call. Per
 * lld/feedback.md §Flow 2 and the LLD style guide §Transaction-AI separation: AI calls must run
 * outside DB transactions.
 *
 * <p>The bean is named so the listener auto-wires it by qualifier; the default {@code
 * TransactionTemplate} bean (if any) is unaffected.
 */
@Configuration
public class FeedbackTxTemplateConfig {

  /** Bean name — referenced via {@code @Qualifier} on the listener. */
  public static final String REQUIRES_NEW_TX_TEMPLATE = "feedbackRequiresNewTxTemplate";

  @Bean(REQUIRES_NEW_TX_TEMPLATE)
  public TransactionTemplate feedbackRequiresNewTxTemplate(PlatformTransactionManager tm) {
    TransactionTemplate template = new TransactionTemplate(tm);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }
}
