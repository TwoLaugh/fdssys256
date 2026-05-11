package com.example.mealprep.nutrition.spi.internal;

import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.exception.DirectiveApplyTargetUnavailableException;
import com.example.mealprep.nutrition.spi.DirectiveApplyTarget;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default {@link DirectiveApplyTarget} bean — active only when no other module has registered one.
 * Logs a WARN and throws {@link DirectiveApplyTargetUnavailableException} so the accept flow
 * surfaces a clear 422 ("preference module not wired for this yet") rather than silently no-op'ing.
 *
 * <p>{@code preference-01c} ships an in-module {@link DirectiveApplyTarget} bean that out-ranks
 * this one via {@code @ConditionalOnMissingBean}.
 *
 * <p>Implementation note: the SPI binding is declared via a {@code @Configuration} class with a
 * {@code @Bean @ConditionalOnMissingBean} method (rather than
 * {@code @Component @ConditionalOnMissingBean} on the class itself). The {@code @Component} variant
 * doesn't reliably defer to other implementations during component-scan ordering. The {@code @Bean}
 * variant evaluates after bean definition gathering, so a real implementation always wins when
 * present.
 */
@Configuration
public class NoopDirectiveApplyTarget {

  private static final Logger log = LoggerFactory.getLogger(NoopDirectiveApplyTarget.class);

  /**
   * Method name distinct from the enclosing class so Spring registers two different bean names
   * ({@code noopDirectiveApplyTarget} for the config class itself, {@code
   * defaultDirectiveApplyTarget} for the SPI binding). Otherwise: {@code
   * BeanDefinitionOverrideException}.
   */
  @Bean
  @ConditionalOnMissingBean(DirectiveApplyTarget.class)
  DirectiveApplyTarget defaultDirectiveApplyTarget() {
    return new NoopDirectiveApplyTargetImpl();
  }

  static class NoopDirectiveApplyTargetImpl implements DirectiveApplyTarget {
    @Override
    public void applyPreferenceDirective(
        UUID userId,
        DirectiveInstructionDocument instruction,
        boolean temporary,
        Instant autoExpiresAt,
        UUID directiveId,
        UUID actorUserId) {
      log.warn(
          "Noop DirectiveApplyTarget invoked — preference-model directive cannot be applied yet."
              + " directiveId={} userId={}",
          directiveId,
          userId);
      throw new DirectiveApplyTargetUnavailableException(
          "preference-model directive routes need preference-01c");
    }
  }
}
