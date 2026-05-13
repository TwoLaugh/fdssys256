package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.domain.service.NutritionalKnowledgeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link NoopNutritionalKnowledgeService} as the default {@link
 * NutritionalKnowledgeService} bean when no other implementation is present in the application
 * context.
 *
 * <p><b>Critical naming rule</b>: the {@code @Bean} factory method name {@link
 * #defaultNutritionalKnowledgeService()} <b>MUST differ</b> from the class name {@code
 * NoopNutritionalKnowledgeService}. Per decisions/0010 round-5 bug-2: if the factory method is
 * named the same as the class, Spring's bean-definition-name collision triggers the
 * {@code @ConditionalOnMissingBean} mis-fire that round 5 surfaced. Keep the names different.
 *
 * <p>This {@code @Configuration} class is the round-5 fix to a more obvious-looking pattern
 * ({@code @Component @ConditionalOnMissingBean} on the Noop class itself). That shape fires the
 * conditional at component-scan, before sibling beans register, so the Noop wins even when a real
 * impl exists. The {@code @Bean}-factory pattern delays the check until bean-definition resolution
 * — the real impl in ticket-01e ({@code NutritionalKnowledgeServiceImpl}) registers first, the
 * conditional sees it, and the Noop is skipped.
 */
@Configuration(proxyBeanMethods = false)
public class NoopNutritionalKnowledgeConfiguration {

  /**
   * Factory method — bean name is {@code defaultNutritionalKnowledgeService}, deliberately distinct
   * from the class name to avoid the round-5 bug-2 collision.
   */
  @Bean
  @ConditionalOnMissingBean(NutritionalKnowledgeService.class)
  NutritionalKnowledgeService defaultNutritionalKnowledgeService() {
    return new NoopNutritionalKnowledgeService();
  }
}
