package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.NutritionFeedbackReverter;
import com.example.mealprep.feedback.spi.PreferenceFeedbackReverter;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackReverter;
import com.example.mealprep.feedback.spi.RecipeFeedbackReverter;
import com.example.mealprep.feedback.spi.RevertContext;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
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
 * Verifies the four {@code *FeedbackReverter} SPIs wire correctly: the three Noop defaults are the
 * resolved beans (they never throw), and a test-scoped {@code @TestConfiguration @Bean} fake {@link
 * PreferenceFeedbackReverter} overrides the Noop via {@code @ConditionalOnMissingBean}.
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
  void noopReverters_doNotThrow() {
    RevertContext n = FeedbackTestData.revertContext(UUID.randomUUID(), Destination.NUTRITION);
    RevertContext p = FeedbackTestData.revertContext(UUID.randomUUID(), Destination.PROVISIONS);
    RevertContext r = FeedbackTestData.revertContext(UUID.randomUUID(), Destination.RECIPE);
    nutritionReverter.revert(n);
    provisionsReverter.revert(p);
    recipeReverter.revert(r);
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
