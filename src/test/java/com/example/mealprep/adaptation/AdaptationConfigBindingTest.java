package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies {@code mealprep.adaptation.*} keys in {@code application.properties} bind to {@link
 * AdaptationConfig} (using the production property defaults loaded by the full Spring Boot test
 * context), and that the Jakarta validation annotations on the record reject obviously-bad
 * overrides on a standalone context.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class AdaptationConfigBindingTest {

  @Autowired private AdaptationConfig config;

  @Test
  void binds_defaults_from_application_properties() {
    assertThat(config.candidateTopN()).isEqualTo(5);
    assertThat(config.planTimeTimeoutMs()).isEqualTo(10_000);
    assertThat(config.feedbackTimeoutMs()).isEqualTo(8_000);
    assertThat(config.importTimeoutMs()).isEqualTo(12_000);
    assertThat(config.maxRebaseAttempts()).isEqualTo(3);
    assertThat(config.pendingChangeBudgetPerWeek()).isEqualTo(3);
    assertThat(config.pendingChangeExpiryDays()).isEqualTo(14);
    assertThat(config.lowConfidenceFloor()).isEqualByComparingTo(new BigDecimal("0.50"));
    assertThat(config.autoSkipTopRatio()).isEqualByComparingTo(new BigDecimal("2.00"));
    assertThat(config.recipeAdvisoryLockSeconds()).isEqualTo(30);
    assertThat(config.pendingExpirySweepCron()).isEqualTo("0 0 4 * * *");
    assertThat(config.batchOrchestratorCron()).isEqualTo("0 30 4 * * *");
    // sourceBudgets defaults to empty per LLD line 715.
    assertThat(config.sourceBudgets()).isEmpty();
  }

  /**
   * The negative-validation case runs on a standalone {@link ApplicationContextRunner} so we don't
   * have to evict the cached full-application context. {@code candidate-top-n=0} violates
   * {@code @Min(1)}; the context refresh must fail with a {@link BindValidationException} root
   * cause.
   *
   * <p>{@link AdaptationConfigBindingStandaloneConfig} is in a sibling file (not a nested class) so
   * Spring Test's default-config scan for {@code @SpringBootTest} above doesn't accidentally pick
   * IT up instead of {@code MealPrepApplication}.
   */
  @Test
  void candidate_top_n_zero_fails_validation() {
    new ApplicationContextRunner()
        .withUserConfiguration(AdaptationConfigBindingStandaloneConfig.class)
        .withPropertyValues(
            "mealprep.adaptation.candidate-top-n=0",
            "mealprep.adaptation.plan-time-timeout-ms=10000",
            "mealprep.adaptation.feedback-timeout-ms=8000",
            "mealprep.adaptation.import-timeout-ms=12000",
            "mealprep.adaptation.max-rebase-attempts=3",
            "mealprep.adaptation.pending-change-budget-per-week=3",
            "mealprep.adaptation.pending-change-expiry-days=14",
            "mealprep.adaptation.low-confidence-floor=0.50",
            "mealprep.adaptation.auto-skip-top-ratio=2.00",
            "mealprep.adaptation.recipe-advisory-lock-seconds=30",
            "mealprep.adaptation.pending-expiry-sweep-cron=0 0 4 * * *",
            "mealprep.adaptation.batch-orchestrator-cron=0 30 4 * * *")
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx.getStartupFailure())
                  .hasRootCauseInstanceOf(BindValidationException.class);
            });
  }
}

/**
 * External standalone config — kept out of {@link AdaptationConfigBindingTest} to keep Spring Boot
 * test-context resolver from picking it as the auto-discovered configuration source.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdaptationConfig.class)
class AdaptationConfigBindingStandaloneConfig {}
