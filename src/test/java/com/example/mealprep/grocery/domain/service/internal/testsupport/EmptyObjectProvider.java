package com.example.mealprep.grocery.domain.service.internal.testsupport;

import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Empty {@link ObjectProvider} for unit tests that construct {@code GroceryServiceImpl} directly
 * without a Spring context (and therefore have no real {@code ObjectProvider} to inject). {@link
 * #getIfAvailable()} returns {@code null} and {@link #getObject()} throws — the same "absent
 * dependency" semantics the runtime gives when no bean of the type is registered.
 *
 * <p>Used by grocery-01g unit tests; lives in {@code testsupport} so production code can't depend
 * on it.
 */
public class EmptyObjectProvider<T> implements ObjectProvider<T> {

  @Override
  public T getObject(Object... args) throws BeansException {
    throw new IllegalStateException("EmptyObjectProvider: no bean available");
  }

  @Override
  public T getObject() throws BeansException {
    throw new IllegalStateException("EmptyObjectProvider: no bean available");
  }

  @Override
  public T getIfAvailable() throws BeansException {
    return null;
  }

  @Override
  public T getIfUnique() throws BeansException {
    return null;
  }

  @Override
  public Iterator<T> iterator() {
    return java.util.Collections.emptyIterator();
  }

  @Override
  public Spliterator<T> spliterator() {
    return Spliterators.emptySpliterator();
  }

  @Override
  public void forEach(Consumer<? super T> action) {
    // no-op
  }

  @Override
  public Stream<T> stream() {
    return StreamSupport.stream(spliterator(), false);
  }

  @Override
  public Stream<T> orderedStream() {
    return stream();
  }
}
