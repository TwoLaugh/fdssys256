package com.example.mealprep.core.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded DEFAULT executor for unqualified {@code @Async} work.
 *
 * <p>{@code @EnableAsync} lives on {@code MealPrepApplication}, but the app declares custom
 * executor beans (e.g. {@code DiscoveryAsyncConfig}, {@code FeedbackAsyncConfig}). Once any
 * user-defined {@link Executor} bean exists, Spring Boot's {@code TaskExecutionAutoConfiguration}
 * backs off and stops contributing its bounded {@code applicationTaskExecutor}. With no resolvable
 * default, bare {@code @Async} silently falls back to an UNBOUNDED {@code SimpleAsyncTaskExecutor}
 * that spawns a fresh thread per task — a thread-exhaustion / OOM risk under load (notably the
 * adaptation Trigger-1 job that fires on every recipe create).
 *
 * <p>This config provides the single bounded default by implementing {@link AsyncConfigurer}:
 * {@link #getAsyncExecutor()} is what Spring's async infrastructure uses to resolve an unqualified
 * {@code @Async}. The same {@link ThreadPoolTaskExecutor} is also published as a {@code @Primary}
 * {@code applicationTaskExecutor} bean so any code that injects {@code Executor} / {@code
 * TaskExecutor} by type (and any framework lookup of that conventional bean name) resolves to the
 * bounded pool rather than tripping an ambiguity against the named module pools.
 *
 * <p>Sizing mirrors {@code FeedbackAsyncConfig}: small core/max, a bounded queue, and {@code
 * CallerRunsPolicy} so an overflowing queue applies back-pressure on the publisher thread instead
 * of throwing {@code RejectedExecutionException} or growing without bound.
 */
@Configuration
public class DefaultAsyncConfig implements AsyncConfigurer {

  /** Conventional Spring Boot default-executor bean name; published {@code @Primary} below. */
  public static final String DEFAULT_POOL = "applicationTaskExecutor";

  private final ThreadPoolTaskExecutor executor = buildExecutor();

  private static ThreadPoolTaskExecutor buildExecutor() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setCorePoolSize(4);
    exec.setMaxPoolSize(8);
    exec.setQueueCapacity(100);
    exec.setThreadNamePrefix("mealprep-async-");
    exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    exec.initialize();
    return exec;
  }

  /**
   * The bean Spring's {@code @Async} infrastructure resolves for unqualified {@code @Async}
   * methods. Returning the same singleton the {@link #applicationTaskExecutor()} bean exposes keeps
   * "the default async executor" a single, bounded instance.
   */
  @Override
  public Executor getAsyncExecutor() {
    return executor;
  }

  /**
   * Publish the bounded executor under the conventional {@code applicationTaskExecutor} name and
   * mark it {@code @Primary} so by-type {@code Executor}/{@code TaskExecutor} injection is
   * unambiguous against the named module pools.
   */
  @Bean(name = DEFAULT_POOL)
  @Primary
  public ThreadPoolTaskExecutor applicationTaskExecutor() {
    return executor;
  }
}
