package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.feedback.spi.Destination;

/**
 * Strategy SPI for the routing fan-out. Per ticket 01d §1: one impl per {@link Destination}.
 *
 * <p>Note: the ticket calls for a package-private interface, but the impls live in the {@code
 * dispatcher/} sub-package per the LLD's package layout. Java's package-private visibility is by
 * exact package, so the interface widens to public to keep the four impl files where the LLD wants
 * them. The impls themselves remain package-private; cross-module reach is by-{@link Destination}
 * through {@link DestinationDispatcherRegistry#resolve(Destination)}, never by importing impls.
 */
public interface DestinationDispatcher {

  Destination destination();

  DispatchResult dispatch(DispatchContext ctx);
}
