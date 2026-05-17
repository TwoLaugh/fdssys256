package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.domain.service.internal.DestinationDispatcher;
import com.example.mealprep.feedback.domain.service.internal.DestinationDispatcherRegistry;
import com.example.mealprep.feedback.spi.Destination;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Startup fail-fast checks for the registry: missing dispatcher for a {@link Destination}, and
 * duplicate registration for the same destination.
 */
class DestinationDispatcherRegistryTest {

  @Test
  void allFour_registers_succeeds_andResolvesEach() {
    DestinationDispatcherRegistry registry =
        new DestinationDispatcherRegistry(
            Arrays.asList(
                stub(Destination.RECIPE),
                stub(Destination.PREFERENCE),
                stub(Destination.NUTRITION),
                stub(Destination.PROVISIONS)));

    for (Destination d : Destination.values()) {
      assertThat(registry.resolve(d).destination()).isEqualTo(d);
    }
  }

  @Test
  void missingDestination_failsAtStartup() {
    List<DestinationDispatcher> dispatchers =
        Arrays.asList(stub(Destination.RECIPE), stub(Destination.PREFERENCE));
    assertThatThrownBy(() -> new DestinationDispatcherRegistry(dispatchers))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing DestinationDispatcher")
        .hasMessageContaining("NUTRITION")
        .hasMessageContaining("PROVISIONS");
  }

  @Test
  void duplicateRegistration_failsAtStartup() {
    List<DestinationDispatcher> dispatchers =
        Arrays.asList(
            stub(Destination.RECIPE),
            stub(Destination.RECIPE),
            stub(Destination.PREFERENCE),
            stub(Destination.NUTRITION),
            stub(Destination.PROVISIONS));
    assertThatThrownBy(() -> new DestinationDispatcherRegistry(dispatchers))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate DestinationDispatcher")
        .hasMessageContaining("RECIPE");
  }

  private static DestinationDispatcher stub(Destination d) {
    DestinationDispatcher m = mock(DestinationDispatcher.class);
    when(m.destination()).thenReturn(d);
    return m;
  }
}
