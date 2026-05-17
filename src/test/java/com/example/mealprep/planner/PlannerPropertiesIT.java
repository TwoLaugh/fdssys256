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
