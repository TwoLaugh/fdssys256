package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
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
 * Verifies the {@code mealprep.planner.*} keys in {@code application.properties} bind to {@link
 * PlannerProperties} and that the Jakarta-validation annotations reject obviously-bad overrides
 * (mirrors {@code AdaptationConfigBindingTest}).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class PlannerPropertiesIT {

  /**
   * The planner-01e scoring keys, supplied to the standalone binder so negative-validation tests
   * isolate the field under test rather than tripping the new {@code @NotNull weights/scoring}.
   */
  private static final String[] SCORING_KEYS = {
    "mealprep.planner.weights.preference=0.143",
    "mealprep.planner.weights.nutrition=0.143",
    "mealprep.planner.weights.cost=0.143",
    "mealprep.planner.weights.variety=0.143",
    "mealprep.planner.weights.time=0.143",
    "mealprep.planner.weights.batch=0.143",
    "mealprep.planner.weights.provisions=0.143",
    "mealprep.planner.scoring.variety.cuisine=5",
    "mealprep.planner.scoring.variety.protein=4",
    "mealprep.planner.scoring.variety.cooking-method=3",
    "mealprep.planner.scoring.variety.max-repeat=2",
    "mealprep.planner.scoring.provisions.waste-value-tiers.above-seven-days=1.0",
    "mealprep.planner.scoring.provisions.waste-value-tiers.three-days-or-less=2.0",
    "mealprep.planner.scoring.provisions.waste-value-tiers.one-day-or-less=3.0",
    "mealprep.planner.scoring.cost.confidence-threshold=0.1",
    "mealprep.planner.stage-c-timeout=PT20S",
    "mealprep.planner.iteration-budget=3",
    "mealprep.planner.max-augmentations=5",
    "mealprep.planner.max-refine-directives=2",
    "mealprep.planner.mid-week.lock-hours-before-slot=24",
    "mealprep.planner.mid-week.max-suggestions-per-plan=3"
  };

  @Autowired private PlannerProperties properties;

  @Test
  void binds_defaults_from_application_properties() {
    assertThat(properties.weekStartDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    assertThat(properties.beamWidth()).isEqualTo(20);
    assertThat(properties.topN()).isEqualTo(5);
    assertThat(properties.minPoolPerSlot()).isEqualTo(3);
    assertThat(properties.maxPoolPerSlot()).isEqualTo(50);
    assertThat(properties.maxTimeOvershootRatio()).isEqualByComparingTo(new BigDecimal("1.5"));
    assertThat(properties.stageATimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.maxAugmentations()).isEqualTo(5);
    assertThat(properties.maxRefineDirectives()).isEqualTo(2);
  }

  @Test
  void binds_stage_c_timeout_and_iteration_budget_planner_01g() {
    assertThat(properties.stageCTimeout()).isEqualTo(Duration.ofSeconds(20));
    assertThat(properties.iterationBudget()).isEqualTo(3);
  }

  @Test
  void binds_scoring_weights_and_tuning_block_planner_01e() {
    assertThat(properties.weights().preference()).isEqualByComparingTo("0.143");
    assertThat(properties.weights().provisions()).isEqualByComparingTo("0.143");
    assertThat(properties.scoring().variety().cuisine()).isEqualTo(5);
    assertThat(properties.scoring().variety().protein()).isEqualTo(4);
    assertThat(properties.scoring().variety().cookingMethod()).isEqualTo(3);
    assertThat(properties.scoring().variety().maxRepeat()).isEqualTo(2);
    assertThat(properties.scoring().provisions().wasteValueTiers().oneDayOrLess())
        .isEqualByComparingTo("3.0");
    assertThat(properties.scoring().cost().confidenceThreshold()).isEqualByComparingTo("0.1");
  }

  @Test
  void binds_mid_week_sub_config_planner_01i() {
    assertThat(properties.midWeek()).isNotNull();
    assertThat(properties.midWeek().lockHoursBeforeSlot()).isEqualTo(24);
    assertThat(properties.midWeek().maxSuggestionsPerPlan()).isEqualTo(3);
  }

  @Test
  void beam_width_zero_fails_validation() {
    new ApplicationContextRunner()
        .withUserConfiguration(PlannerPropertiesBindStandaloneConfig.class)
        .withPropertyValues(
            "mealprep.planner.week-start-day-of-week=MONDAY",
            "mealprep.planner.beam-width=0",
            "mealprep.planner.top-n=5",
            "mealprep.planner.min-pool-per-slot=3",
            "mealprep.planner.max-pool-per-slot=50",
            "mealprep.planner.max-time-overshoot-ratio=1.5",
            "mealprep.planner.stage-a-timeout=PT30S")
        .withPropertyValues(SCORING_KEYS)
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx.getStartupFailure())
                  .hasRootCauseInstanceOf(BindValidationException.class);
            });
  }

  @Test
  void max_time_overshoot_ratio_out_of_range_fails_validation() {
    new ApplicationContextRunner()
        .withUserConfiguration(PlannerPropertiesBindStandaloneConfig.class)
        .withPropertyValues(
            "mealprep.planner.week-start-day-of-week=MONDAY",
            "mealprep.planner.beam-width=20",
            "mealprep.planner.top-n=5",
            "mealprep.planner.min-pool-per-slot=3",
            "mealprep.planner.max-pool-per-slot=50",
            "mealprep.planner.max-time-overshoot-ratio=3.5",
            "mealprep.planner.stage-a-timeout=PT30S")
        .withPropertyValues(SCORING_KEYS)
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx.getStartupFailure())
                  .hasRootCauseInstanceOf(BindValidationException.class);
            });
  }
}

/**
 * External standalone config — kept out of {@link PlannerPropertiesIT} to keep Spring Boot's
 * test-context resolver from picking it as the auto-discovered configuration source.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlannerProperties.class)
class PlannerPropertiesBindStandaloneConfig {}
