package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.feedback.spi.Destination;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Aggregates the {@link DestinationDispatcher} {@code @Component} beans into an immutable {@code
 * Map<Destination, DestinationDispatcher>}. Fails-fast at startup on missing or duplicate
 * registrations per ticket 01d §5.
 */
@Component
public class DestinationDispatcherRegistry {

  private final Map<Destination, DestinationDispatcher> byDestination;

  public DestinationDispatcherRegistry(List<DestinationDispatcher> dispatchers) {
    Map<Destination, DestinationDispatcher> map = new EnumMap<>(Destination.class);
    for (DestinationDispatcher d : dispatchers) {
      DestinationDispatcher prev = map.put(d.destination(), d);
      if (prev != null) {
        throw new IllegalStateException(
            "duplicate DestinationDispatcher for "
                + d.destination()
                + ": "
                + prev.getClass().getName()
                + " and "
                + d.getClass().getName());
      }
    }
    if (map.keySet().size() != Destination.values().length) {
      Set<Destination> missing = EnumSet.allOf(Destination.class);
      missing.removeAll(map.keySet());
      throw new IllegalStateException(
          "missing DestinationDispatcher for: "
              + missing.stream().map(Enum::name).collect(Collectors.joining(",")));
    }
    this.byDestination = Map.copyOf(map);
  }

  public DestinationDispatcher resolve(Destination d) {
    DestinationDispatcher dispatcher = byDestination.get(d);
    if (dispatcher == null) {
      throw new IllegalStateException("no DestinationDispatcher for " + d);
    }
    return dispatcher;
  }
}
