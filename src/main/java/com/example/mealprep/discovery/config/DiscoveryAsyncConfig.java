package com.example.mealprep.discovery.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async config skeleton for the discovery runner. Ships the {@code discoveryRunnerExecutor} bean
 * stub in 01a so 01d's {@code @Async("discoveryRunnerExecutor")} on {@code
 * DiscoveryJobRunner.run} resolves from day one.
 *
 * <p>Pool sizing per LLD line 566: I/O-bound work, small parallelism. {@code CallerRunsPolicy}
 * provides the simplest backpressure when the queue is full — the publisher thread executes the
 * task itself rather than throwing {@code RejectedExecutionException}. 01d may revise once it sees
 * real load.
 *
 * <p>{@code @EnableAsync} is already enabled project-wide on {@code MealPrepApplication}; this
 * config just contributes the named executor.
 */
@Configuration
@EnableConfigurationProperties(DiscoveryProperties.class)
public class DiscoveryAsyncConfig {

  @Bean(name = "discoveryRunnerExecutor")
  public ThreadPoolTaskExecutor discoveryRunnerExecutor() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setCorePoolSize(2);
    exec.setMaxPoolSize(4);
    exec.setQueueCapacity(8);
    exec.setThreadNamePrefix("discovery-runner-");
    exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    exec.initialize();
    return exec;
  }
}
