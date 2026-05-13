package com.example.mealprep.feedback.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configures the dedicated executor for async feedback classification. Sized per LLD §Flow 2 line
 * 726: four threads, queue-100, {@code CallerRunsPolicy} as the back-pressure strategy.
 *
 * <p>In 01b this bean is wired but unused — no {@code @Async} annotation references it yet. 01c
 * adds the classification listener with {@code @Async(FeedbackAsyncConfig.CLASSIFICATION_POOL)}.
 *
 * <p>Project-wide {@code @EnableAsync} lives on {@code MealPrepApplication}; no module-level
 * annotation needed here.
 */
@Configuration
public class FeedbackAsyncConfig {

  /** Bean-name constant — referenced by 01c's classification listener. */
  public static final String CLASSIFICATION_POOL = "feedbackClassificationPool";

  @Bean(CLASSIFICATION_POOL)
  public ThreadPoolTaskExecutor feedbackClassificationPool() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setCorePoolSize(4);
    exec.setMaxPoolSize(4);
    exec.setQueueCapacity(100);
    exec.setThreadNamePrefix("feedback-classify-");
    exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    exec.initialize();
    return exec;
  }
}
