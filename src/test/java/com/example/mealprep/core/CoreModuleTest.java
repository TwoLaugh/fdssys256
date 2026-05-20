package com.example.mealprep.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.audit.domain.service.DecisionLogQueryService;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.core.lock.LockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-unit pinning of the {@link CoreModule} facade. Each getter must return the exact instance
 * passed to the constructor — Pitest's {@code NullReturnVals} mutator on the three accessors
 * (`decisionLog()`, `decisionLogQuery()`, `lock()`) survives until each one is asserted
 * non-null-and-equal. {@code isSameAs} catches both "replaced with null" and any future identity-
 * preserving mutation (e.g. cloning, copy constructors).
 */
@ExtendWith(MockitoExtension.class)
class CoreModuleTest {

  @Mock private DecisionLogService decisionLogService;
  @Mock private DecisionLogQueryService decisionLogQueryService;
  @Mock private LockService lockService;

  @Test
  void decisionLog_returns_injected_write_service() {
    // Kills CoreModule.java:34 NullReturnVals — decisionLog() replaced with null.
    CoreModule module = new CoreModule(decisionLogService, decisionLogQueryService, lockService);

    assertThat(module.decisionLog()).isSameAs(decisionLogService);
  }

  @Test
  void decisionLogQuery_returns_injected_read_service() {
    // Kills CoreModule.java:38 NullReturnVals — decisionLogQuery() replaced with null.
    CoreModule module = new CoreModule(decisionLogService, decisionLogQueryService, lockService);

    assertThat(module.decisionLogQuery()).isSameAs(decisionLogQueryService);
  }

  @Test
  void lock_returns_injected_lock_service() {
    // Kills CoreModule.java:42 NullReturnVals — lock() replaced with null.
    CoreModule module = new CoreModule(decisionLogService, decisionLogQueryService, lockService);

    assertThat(module.lock()).isSameAs(lockService);
  }

  @Test
  void all_three_getters_round_trip_distinct_instances() {
    // Defence in depth: if a future refactor accidentally wires the same dep to two getters,
    // this test fails. Each accessor must return its own constructor argument.
    CoreModule module = new CoreModule(decisionLogService, decisionLogQueryService, lockService);

    assertThat(module.decisionLog()).isNotSameAs(module.decisionLogQuery());
    assertThat(module.decisionLog()).isNotSameAs(module.lock());
    assertThat(module.decisionLogQuery()).isNotSameAs(module.lock());
  }
}
