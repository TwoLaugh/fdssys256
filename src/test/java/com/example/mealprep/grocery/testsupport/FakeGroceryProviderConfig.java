package com.example.mealprep.grocery.testsupport;

import com.example.mealprep.grocery.domain.service.ReferencePriceSource;
import com.example.mealprep.grocery.domain.service.internal.providers.FakeGroceryProvider;
import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registers the test-scoped {@link FakeGroceryProvider} as a {@code GroceryProvider} bean for the
 * Tier-3 order ITs (grocery-01e). The fake derives its quotes from the real {@link
 * ReferencePriceSource} bean; the {@link Clock} is the application's. {@code @Import} this from any
 * IT that needs a live provider.
 */
@TestConfiguration
public class FakeGroceryProviderConfig {

  @Bean
  public FakeGroceryProvider fakeGroceryProvider(
      ReferencePriceSource referencePriceSource, Clock clock) {
    return new FakeGroceryProvider(referencePriceSource, clock);
  }
}
