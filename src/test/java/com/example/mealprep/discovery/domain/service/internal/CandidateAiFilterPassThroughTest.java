package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CandidateAiFilterPassThroughTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(NoopCandidateAiFilterConfiguration.class);

  @Test
  void passThroughReturnsInputUnchanged() {
    contextRunner.run(
        ctx -> {
          CandidateAiFilter filter = ctx.getBean(CandidateAiFilter.class);
          List<DiscoveryCandidate> input =
              List.of(
                  new DiscoveryCandidate(
                      "src_a", "https://example.test/r1", "title", "desc", Map.of("rank", "1")));

          List<DiscoveryCandidate> result =
              filter.filter(input, DiscoveryTestData.sampleConstraints(), UUID.randomUUID());

          // Reference equality preferred per ticket edge-case 35.
          assertThat(result).isSameAs(input);
        });
  }

  @Test
  void noopBeanIsRegistered() {
    contextRunner.run(ctx -> assertThat(ctx).hasSingleBean(CandidateAiFilter.class));
  }
}
