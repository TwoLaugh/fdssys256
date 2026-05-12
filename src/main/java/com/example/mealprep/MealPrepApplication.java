package com.example.mealprep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Recipe-01g introduces {@code @Scheduled} jobs (specifically {@code ArchiveEligibilityScanner});
 * {@code @EnableScheduling} is required at the application root for cron triggers to fire.
 */
@SpringBootApplication
@EnableScheduling
public class MealPrepApplication {

  public static void main(String[] args) {
    SpringApplication.run(MealPrepApplication.class, args);
  }
}
