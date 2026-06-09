package com.example.mealprep.discovery.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async config skeleton for the discovery runner. Ships the {@code discoveryRunnerExecutor} bean
 * stub in 01a so 01d's {@code @Async("discoveryRunnerExecutor")} on {@code DiscoveryJobRunner.run}
 * resolves from day one.
 *
 * <p>Pool sizing per LLD line 566: I/O-bound work, small parallelism. {@code CallerRunsPolicy}
 * provides the simplest backpressure when the queue is full — the publisher thread executes the
 * task itself rather than throwing {@code RejectedExecutionException}. 01d may revise once it sees
 * real load.
 *
 * <p>{@code @EnableAsync} is already enabled project-wide on {@code MealPrepApplication}; this
 * config just contributes the named executor.
 *
 * <p><strong>{@code discoverySourceFanoutExecutor}</strong> — a SEPARATE bounded pool used ONLY by
 * the runner's opt-in cross-source search fan-out (feature flag {@code
 * mealprep.discovery.parallel-sources=true}; default off). It is deliberately distinct from {@code
 * discoveryRunnerExecutor}: the per-job {@code run()} task already occupies a {@code
 * discoveryRunnerExecutor} thread and blocks on {@code allOf(...)} while the fan-out runs, so
 * reusing the same pool for the sub-tasks could starve / deadlock under concurrent jobs (the job
 * thread holds one of core=2 slots while its sub-tasks queue behind sibling jobs' {@code run()}
 * tasks). A dedicated bounded pool with {@code CallerRunsPolicy} keeps the worst case to "fan-out
 * degrades to running a source on the calling runner thread" — still bounded, never unbounded
 * thread creation. Daemon threads so a shutdown is never blocked by an in-flight search.
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

  /**
   * Bounded pool for the within-job cross-source search fan-out (flag-gated, default off). Core ==
   * max == 4 keeps it small (discovery has a tiny locked source count in v1); the bounded queue +
   * {@code CallerRunsPolicy} means saturation degrades to inline execution on the submitting runner
   * thread rather than unbounded growth or rejection. Threads are daemons and time out when idle so
   * the pool costs nothing when the flag is off.
   */
  @Bean(name = "discoverySourceFanoutExecutor", destroyMethod = "shutdown")
  public ExecutorService discoverySourceFanoutExecutor() {
    ThreadPoolExecutor exec =
        new ThreadPoolExecutor(
            4,
            4,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(16),
            new DaemonThreadFactory("discovery-source-fanout-"),
            new ThreadPoolExecutor.CallerRunsPolicy());
    exec.allowCoreThreadTimeOut(true);
    return exec;
  }

  /** Names threads and marks them daemon so an in-flight fan-out never blocks JVM shutdown. */
  private static final class DaemonThreadFactory implements java.util.concurrent.ThreadFactory {
    private final String prefix;
    private final java.util.concurrent.atomic.AtomicInteger counter =
        new java.util.concurrent.atomic.AtomicInteger();

    DaemonThreadFactory(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r, prefix + counter.incrementAndGet());
      t.setDaemon(true);
      return t;
    }
  }
}
