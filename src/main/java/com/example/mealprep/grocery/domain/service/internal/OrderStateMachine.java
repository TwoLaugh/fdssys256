package com.example.mealprep.grocery.domain.service.internal;

import static com.example.mealprep.grocery.domain.entity.GroceryOrderStatus.ARCHIVED;
import static com.example.mealprep.grocery.domain.entity.GroceryOrderStatus.AWAITING_USER_CONFIRMATION;
import static com.example.mealprep.grocery.domain.entity.GroceryOrderStatus.CANCELLED;
import static com.example.mealprep.grocery.domain.entity.GroceryOrderStatus.CONFIRMED;
import static com.example.mealprep.grocery.domain.entity.GroceryOrderStatus.DELIVERED;
import static com.example.mealprep.grocery.domain.entity.GroceryOrderStatus.DRAFT;
import static com.example.mealprep.grocery.domain.entity.GroceryOrderStatus.PLACED;
import static com.example.mealprep.grocery.domain.entity.GroceryOrderStatus.PLACED_PARTIAL;
import static com.example.mealprep.grocery.domain.entity.GroceryOrderStatus.PROVIDER_UNAVAILABLE;
import static com.example.mealprep.grocery.domain.entity.GroceryOrderStatus.QUOTED;
import static com.example.mealprep.grocery.domain.entity.GroceryOrderStatus.RECONCILED;

import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.exception.IllegalOrderTransitionException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The order-lifecycle state machine (grocery-01e). Per lld/grocery.md lines 780-796. The
 * legal-edges table is VERBATIM from the ticket / LLD:
 *
 * <pre>
 * draft                      → quoted, cancelled
 * quoted                     → placed, placed_partial, draft, cancelled
 * placed                     → awaiting_user_confirmation, cancelled, provider_unavailable
 * placed_partial             → awaiting_user_confirmation, cancelled
 * awaiting_user_confirmation → confirmed, cancelled
 * confirmed                  → delivered, cancelled
 * delivered                  → reconciled, cancelled
 * reconciled                 → archived
 * provider_unavailable       → draft, cancelled
 * cancelled                  → (terminal)
 * archived                   → (terminal)
 * </pre>
 *
 * <p>{@link #assertCanTransition} throws {@link IllegalOrderTransitionException} (409) on any edge
 * not in the table. Package-private internal plumbing.
 */
@Component
class OrderStateMachine {

  private static final Map<GroceryOrderStatus, Set<GroceryOrderStatus>> EDGES =
      new EnumMap<>(GroceryOrderStatus.class);

  static {
    EDGES.put(DRAFT, EnumSet.of(QUOTED, CANCELLED));
    EDGES.put(QUOTED, EnumSet.of(PLACED, PLACED_PARTIAL, DRAFT, CANCELLED));
    EDGES.put(PLACED, EnumSet.of(AWAITING_USER_CONFIRMATION, CANCELLED, PROVIDER_UNAVAILABLE));
    EDGES.put(PLACED_PARTIAL, EnumSet.of(AWAITING_USER_CONFIRMATION, CANCELLED));
    EDGES.put(AWAITING_USER_CONFIRMATION, EnumSet.of(CONFIRMED, CANCELLED));
    EDGES.put(CONFIRMED, EnumSet.of(DELIVERED, CANCELLED));
    EDGES.put(DELIVERED, EnumSet.of(RECONCILED, CANCELLED));
    EDGES.put(RECONCILED, EnumSet.of(ARCHIVED));
    EDGES.put(PROVIDER_UNAVAILABLE, EnumSet.of(DRAFT, CANCELLED));
    EDGES.put(CANCELLED, EnumSet.noneOf(GroceryOrderStatus.class));
    EDGES.put(ARCHIVED, EnumSet.noneOf(GroceryOrderStatus.class));
  }

  /** Whether {@code current → target} is a legal edge. */
  boolean canTransition(GroceryOrderStatus current, GroceryOrderStatus target) {
    return EDGES.getOrDefault(current, Set.of()).contains(target);
  }

  /** The legal outgoing targets for {@code current} (empty for the terminal states). */
  Set<GroceryOrderStatus> legalTargets(GroceryOrderStatus current) {
    return EDGES.getOrDefault(current, Set.of());
  }

  /**
   * Assert {@code current → target} is legal, else throw {@link IllegalOrderTransitionException}
   * (409). A no-op self-edge ({@code current == target}) is NOT legal unless listed in the table.
   */
  void assertCanTransition(GroceryOrderStatus current, GroceryOrderStatus target) {
    if (!canTransition(current, target)) {
      throw new IllegalOrderTransitionException(current, target);
    }
  }
}
