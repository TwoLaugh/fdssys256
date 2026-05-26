package com.example.mealprep.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Guards the bounded DEFAULT async executor (fix #4). Without a resolvable default, bare
 * {@code @Async} falls back to the UNBOUNDED {@code SimpleAsyncTaskExecutor}; these assertions pin
 * that the default is a {@link ThreadPoolTaskExecutor} with a bounded pool + queue and a {@code
 * CallerRunsPolicy} back-pressure handler.
 */
class DefaultAsyncConfigTest {

  @Test
  void defaultExecutorIsBoundedThreadPool() {
    DefaultAsyncConfig config = new DefaultAsyncConfig();

    Executor asyncExecutor = config.getAsyncExecutor();
    ThreadPoolTaskExecutor bean = config.applicationTaskExecutor();

    // The async-infrastructure executor and the published bean are the SAME bounded instance, so
    // there is exactly one default and no ambiguity for unqualified @Async.
    assertThat(asyncExecutor).isSameAs(bean);
    assertThat(asyncExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);

    assertThat(bean.getCorePoolSize()).isPositive();
    assertThat(bean.getMaxPoolSize()).isPositive().isNotEqualTo(Integer.MAX_VALUE);
    // A bounded queue (capacity > 0 and not the unbounded sentinel) is what stops runaway growth.
    assertThat(bean.getQueueCapacity()).isPositive().isNotEqualTo(Integer.MAX_VALUE);

    ThreadPoolExecutor pool = bean.getThreadPoolExecutor();
    assertThat(pool.getRejectedExecutionHandler())
        .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
  }
}
