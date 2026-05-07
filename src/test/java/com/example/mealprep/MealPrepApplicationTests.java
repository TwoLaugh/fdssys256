package com.example.mealprep;

import com.example.mealprep.testsupport.TestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test asserting the Spring context starts in the {@code test} profile.
 *
 * <p>{@link TestContainersConfig} provides the Postgres + pgvector container via
 * {@code @ServiceConnection}; without it, Flyway's first migration ({@code CREATE EXTENSION
 * vector}) fails on the upstream {@code postgres} image.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class MealPrepApplicationTests {

  @Test
  void contextLoads() {
    // Spring will fail the test if the context can't load.
  }
}
