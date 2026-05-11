package com.example.mealprep.household.testdata;

import com.example.mealprep.household.api.dto.SoftPreferenceBundleDto;
import com.example.mealprep.household.spi.SoftPreferencesReader;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Test-only helpers for wiring {@link SoftPreferencesReader} via an {@link ObjectProvider} in
 * unit-test fixtures where the production SPI lookup machinery isn't running.
 */
public final class SoftPreferencesReaderTestSupport {

  private SoftPreferencesReaderTestSupport() {}

  /** Provider returning an inline noop reader (always {@code List.of()}). */
  public static ObjectProvider<SoftPreferencesReader> emptyProvider() {
    return providerOf(userIds -> List.of());
  }

  /** Provider returning the given reader for every {@code getObject} call. */
  public static ObjectProvider<SoftPreferencesReader> providerOf(SoftPreferencesReader reader) {
    return new ObjectProvider<>() {
      @Override
      public SoftPreferencesReader getObject() throws BeansException {
        return reader;
      }

      @Override
      public SoftPreferencesReader getObject(Object... args) throws BeansException {
        return reader;
      }

      @Override
      public SoftPreferencesReader getIfAvailable() throws BeansException {
        return reader;
      }

      @Override
      public SoftPreferencesReader getIfUnique() throws BeansException {
        return reader;
      }

      @Override
      public void forEach(Consumer<? super SoftPreferencesReader> action) {
        action.accept(reader);
      }

      @Override
      public Iterator<SoftPreferencesReader> iterator() {
        return List.of(reader).iterator();
      }

      @Override
      public Stream<SoftPreferencesReader> stream() {
        return Stream.of(reader);
      }

      @Override
      public Stream<SoftPreferencesReader> orderedStream() {
        return Stream.of(reader);
      }
    };
  }

  /** Build a fake reader returning {@code bundles} verbatim for any input. */
  public static SoftPreferencesReader fixedReader(List<SoftPreferenceBundleDto> bundles) {
    return (List<UUID> userIds) -> bundles;
  }
}
