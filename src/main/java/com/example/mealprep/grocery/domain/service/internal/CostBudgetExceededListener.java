package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.ai.event.CostBudgetExceededEvent;
import com.example.mealprep.grocery.domain.entity.GroceryProviderState;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pauses scheduled background refresh for a user when the AI cost cap fires (LLD line 849).
 *
 * <p>Listens for {@link CostBudgetExceededEvent} published by the {@code ai} module's {@code
 * CostBudgetGuard} after a per-user cap rejection. Flips {@code scheduled_refresh_enabled} to
 * {@code false} on every {@link GroceryProviderState} row owned by the affected user — the weekly
 * refresh's {@code findAllByScheduledRefreshEnabledTrue} call will no longer include them until the
 * user explicitly re-enables scheduled refresh from the connection-management surface (per LLD Flow
 * 8, line 963).
 *
 * <p>Runs {@code AFTER_COMMIT} of the AI module's writing transaction and in its own {@code
 * REQUIRES_NEW} transaction — the listener must not roll back the publisher's commit, and its own
 * DB writes need to commit independently of the (already-committed) publisher (LLD lines 95-99 /
 * style guide §events).
 */
@Component
public class CostBudgetExceededListener {

  private static final Logger log = LoggerFactory.getLogger(CostBudgetExceededListener.class);

  private final GroceryOrderDataGateway gateway;

  public CostBudgetExceededListener(GroceryOrderDataGateway gateway) {
    this.gateway = gateway;
  }

  /**
   * Disable scheduled refresh on every provider state owned by the affected user. Idempotent — if a
   * row is already disabled we still write through the gateway (the {@code @Version} bump is the
   * audit signal); no-op overall when the user has no provider state.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onCostBudgetExceeded(CostBudgetExceededEvent event) {
    if (event == null || event.userId() == null) {
      return;
    }
    List<GroceryProviderState> states = gateway.findProviderStatesByUserId(event.userId());
    if (states.isEmpty()) {
      return;
    }
    int flipped = 0;
    for (GroceryProviderState state : states) {
      if (state.isScheduledRefreshEnabled()) {
        state.setScheduledRefreshEnabled(false);
        gateway.saveProviderState(state);
        flipped++;
      }
    }
    if (flipped > 0) {
      log.info(
          "AI cost cap fired for user {} — paused scheduled refresh on {} provider state row(s)",
          event.userId(),
          flipped);
    }
  }
}
