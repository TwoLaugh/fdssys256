package com.example.mealprep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Recipe-01g introduces {@code @Scheduled} jobs (specifically {@code ArchiveEligibilityScanner});
 * {@code @EnableScheduling} is required at the application root for cron triggers to fire.
 *
 * <p>Recipe-01h introduces {@code @Async @TransactionalEventListener} on {@code
 * RecipeEmbeddingListener} so the OpenAI embedding call doesn't block the publisher's post-commit
 * thread; {@code @EnableAsync} engages Spring's proxy-based async support.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class MealPrepApplication {

  public static void main(String[] args) {
    SpringApplication.run(MealPrepApplication.class, args);
  }
}
