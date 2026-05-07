package com.example.mealprep;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test asserting the Spring context starts in the {@code test} profile.
 *
 * <p>The {@code application-test.properties} datasource URL uses the Testcontainers JDBC URL
 * convention ({@code jdbc:tc:postgresql:16-alpine:///mealprep}), so {@code @SpringBootTest} spins
 * up a Postgres container automatically — no explicit {@code @Container} field needed here.
 */
@SpringBootTest
@ActiveProfiles("test")
class MealPrepApplicationTests {

  @Test
  void contextLoads() {
    // Spring will fail the test if the context can't load.
  }
}
