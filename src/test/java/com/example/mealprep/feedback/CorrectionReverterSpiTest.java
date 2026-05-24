package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.mealprep.adaptation.spi.internal.RecipeFeedbackReverterImpl;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.NutritionFeedbackReverter;
import com.example.mealprep.feedback.spi.PreferenceFeedbackReverter;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackReverter;
import com.example.mealprep.feedback.spi.RecipeFeedbackReverter;
import com.example.mealprep.feedback.spi.RevertContext;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import com.example.mealprep.nutrition.spi.internal.NutritionFeedbackReverterImpl;
import com.example.mealprep.provisions.spi.internal.ProvisionsFeedbackReverterImpl;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the four {@code *FeedbackReverter} SPIs wire correctly with feedback-01h's real impls on
 * the classpath: each destination module's {@code @Component} reverter out-ranks its Noop default
 * via {@code @ConditionalOnMissingBean}, so the resolved bean is the real impl (NUTRITION /
 * PROVISIONS / RECIPE). The PREFERENCE slot is additionally overridden by a
 * {@code @TestConfiguration @Bean @Primary} recording fake so the wiring + invocation are
 * observable without seeding a profile. All resolved beans degrade gracefully (never throw) on a
 * minimal {@link RevertContext} carrying no resolvable correlation handle.
 */
@SpringBootTest
@Import({TestContainersConfig.class, CorrectionReverterSpiTest.FakeReverterConfig.class})
@ActiveProfiles("test")
class CorrectionReverterSpiTest {

  static final AtomicReference<RevertContext> CAPTURED = new AtomicReference<>();

  @Autowired private PreferenceFeedbackReverter preferenceReverter;
  @Autowired private NutritionFeedbackReverter nutritionReverter;
  @Autowired private ProvisionsFeedbackReverter provisionsReverter;
  @Autowired private RecipeFeedbackReverter recipeReverter;

  @Test
  void fakePreferenceReverter_overridesNoop_andIsInvoked() {
    CAPTURED.set(null);
    RevertContext ctx = FeedbackTestData.revertContext(UUID.randomUUID(), Destination.PREFERENCE);
    preferenceReverter.revert(ctx);
    assertThat(CAPTURED.get()).isSameAs(ctx);
  }

  @Test
  void realReverters_winOverNoops_whenDestinationModuleOnClasspath() {
    // The destination modules are all on the @SpringBootTest classpath, so each real @Component
    // reverter is the resolved bean (not the feedback-module Noop).
    assertThat(nutritionReverter).isInstanceOf(NutritionFeedbackReverterImpl.class);
    assertThat(provisionsReverter).isInstanceOf(ProvisionsFeedbackReverterImpl.class);
    assertThat(recipeReverter).isInstanceOf(RecipeFeedbackReverterImpl.class);
  }

  @Test
  void realReverters_degradeGracefully_onMinimalContext() {
    // A context with no resolvable handle (no jobId / origin trace) must log-only, never throw —
    // the SPI contract requires the reverter not to block the correction.
    RevertContext n = FeedbackTestData.revertContext(UUID.randomUUID(), Destination.NUTRITION);
    RevertContext p = FeedbackTestData.revertContext(UUID.randomUUID(), Destination.PROVISIONS);
    RevertContext r = FeedbackTestData.revertContext(UUID.randomUUID(), Destination.RECIPE);
    assertThatCode(() -> nutritionReverter.revert(n)).doesNotThrowAnyException();
    assertThatCode(() -> provisionsReverter.revert(p)).doesNotThrowAnyException();
    assertThatCode(() -> recipeReverter.revert(r)).doesNotThrowAnyException();
  }

  @TestConfiguration
  static class FakeReverterConfig {
    // @Primary: the Noop defaultPreferenceReverter is @ConditionalOnMissingBean, but the
    // conditional evaluates before this @TestConfiguration import registers, so BOTH beans exist
    // -> NoUniqueBeanDefinitionException. @Primary resolves the ambiguity to the fake (round-6
    // retro: test-side override of an SPI-with-Noop needs @Primary, not just the conditional).
    @Bean
    @Primary
    PreferenceFeedbackReverter fakePreferenceReverter() {
      return CAPTURED::set;
    }
  }
}
