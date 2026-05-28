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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.exception.IllegalOrderTransitionException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link OrderStateMachine} (grocery-01e). Per lld/grocery.md line 1016 / the ticket
 * edge-case checklist: every legal edge passes; a sample of illegal edges throws 409; every
 * non-terminal status has at least one outgoing edge (graph-completeness). The legal-edges table is
 * duplicated here VERBATIM as the test oracle so a regression in the production table is caught.
 */
class OrderStateMachineTest {

  private final OrderStateMachine stateMachine = new OrderStateMachine();

  /** The legal-edges table verbatim (the test oracle, independent of the production map). */
  private static final Map<GroceryOrderStatus, Set<GroceryOrderStatus>> LEGAL =
      Map.ofEntries(
          Map.entry(DRAFT, Set.of(QUOTED, CANCELLED)),
          Map.entry(QUOTED, Set.of(PLACED, PLACED_PARTIAL, DRAFT, CANCELLED)),
          Map.entry(PLACED, Set.of(AWAITING_USER_CONFIRMATION, CANCELLED, PROVIDER_UNAVAILABLE)),
          Map.entry(PLACED_PARTIAL, Set.of(AWAITING_USER_CONFIRMATION, CANCELLED)),
          Map.entry(AWAITING_USER_CONFIRMATION, Set.of(CONFIRMED, CANCELLED)),
          Map.entry(CONFIRMED, Set.of(DELIVERED, CANCELLED)),
          Map.entry(DELIVERED, Set.of(RECONCILED, CANCELLED)),
          Map.entry(RECONCILED, Set.of(ARCHIVED)),
          Map.entry(PROVIDER_UNAVAILABLE, Set.of(DRAFT, CANCELLED)),
          Map.entry(CANCELLED, Set.of()),
          Map.entry(ARCHIVED, Set.of()));

  private static final Set<GroceryOrderStatus> TERMINAL = Set.of(CANCELLED, ARCHIVED);

  @Test
  void everyLegalEdge_passes() {
    for (var entry : LEGAL.entrySet()) {
      GroceryOrderStatus from = entry.getKey();
      for (GroceryOrderStatus to : entry.getValue()) {
        assertThat(stateMachine.canTransition(from, to))
            .as("legal edge %s → %s", from, to)
            .isTrue();
        // assertCanTransition must NOT throw on a legal edge.
        stateMachine.assertCanTransition(from, to);
      }
    }
  }

  @Test
  void everyIllegalEdge_isRejected_andAssertThrows409() {
    for (GroceryOrderStatus from : GroceryOrderStatus.values()) {
      Set<GroceryOrderStatus> legalTargets = LEGAL.getOrDefault(from, Set.of());
      for (GroceryOrderStatus to : GroceryOrderStatus.values()) {
        if (legalTargets.contains(to)) {
          continue;
        }
        assertThat(stateMachine.canTransition(from, to))
            .as("illegal edge %s → %s must be rejected", from, to)
            .isFalse();
        assertThatThrownBy(() -> stateMachine.assertCanTransition(from, to))
            .as("assertCanTransition throws on illegal edge %s → %s", from, to)
            .isInstanceOf(IllegalOrderTransitionException.class);
      }
    }
  }

  @Test
  void illegalSample_throwsWithFromAndTo() {
    // A representative illegal edge from the checklist: cancel after reconciled (GROC-24).
    assertThatThrownBy(() -> stateMachine.assertCanTransition(RECONCILED, CANCELLED))
        .isInstanceOf(IllegalOrderTransitionException.class)
        .satisfies(
            ex -> {
              IllegalOrderTransitionException e = (IllegalOrderTransitionException) ex;
              assertThat(e.from()).isEqualTo(RECONCILED);
              assertThat(e.to()).isEqualTo(CANCELLED);
            });
    // And from archived (terminal) — also illegal.
    assertThatThrownBy(() -> stateMachine.assertCanTransition(ARCHIVED, DRAFT))
        .isInstanceOf(IllegalOrderTransitionException.class);
  }

  @Test
  void everyNonTerminalStatus_hasAtLeastOneOutgoingEdge() {
    for (GroceryOrderStatus status : GroceryOrderStatus.values()) {
      if (TERMINAL.contains(status)) {
        assertThat(stateMachine.legalTargets(status))
            .as("terminal status %s has no outgoing edges", status)
            .isEmpty();
      } else {
        assertThat(stateMachine.legalTargets(status))
            .as("non-terminal status %s must have at least one outgoing edge", status)
            .isNotEmpty();
      }
    }
  }

  @Test
  void productionTable_matchesTheOracleExactly() {
    // Guard: the production map and the oracle agree on EVERY (from → targets) set.
    for (GroceryOrderStatus from : GroceryOrderStatus.values()) {
      assertThat(stateMachine.legalTargets(from))
          .as("production targets for %s", from)
          .containsExactlyInAnyOrderElementsOf(LEGAL.getOrDefault(from, Set.of()));
    }
    // And every status is covered (no missing rows).
    assertThat(LEGAL.keySet()).containsExactlyInAnyOrder(GroceryOrderStatus.values());
    // Self-edges are not legal (no row lists itself).
    for (var entry : LEGAL.entrySet()) {
      assertThat(entry.getValue()).doesNotContain(entry.getKey());
    }
    // The full status set is the 11 the enum declares.
    assertThat(List.of(GroceryOrderStatus.values())).hasSize(11);
  }
}
