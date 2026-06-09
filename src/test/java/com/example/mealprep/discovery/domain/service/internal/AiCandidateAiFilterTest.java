package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.discovery.config.DiscoveryProperties;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests over the real {@link AiCandidateAiFilter}. Covers the discovery-3 skip-and-flag
 * failure contract (AI dispatch failure → candidate KEPT, never silently dropped) and the
 * discovery-4 partitioning (model rejections surfaced in {@link CandidateFilterOutcome#rejected()}
 * so the runner can emit AI_FILTER_REJECTED scrape rows).
 */
@ExtendWith(MockitoExtension.class)
class AiCandidateAiFilterTest {

  private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Mock private AiService aiService;

  private AiCandidateAiFilter filter;

  @BeforeEach
  void setUp() {
    DiscoveryProperties properties =
        new DiscoveryProperties(
            Duration.ofMinutes(10),
            30,
            Duration.ofSeconds(60),
            Duration.ofHours(1),
            Duration.ofHours(6),
            new BigDecimal("0.6"),
            false,
            null);
    filter = new AiCandidateAiFilter(aiService, properties);
  }

  private DiscoveryConstraints constraints() {
    return DiscoveryTestData.sampleConstraints();
  }

  private DiscoveryCandidate candidate(String url) {
    return new DiscoveryCandidate("src_a", url, "title", "snippet", Map.of());
  }

  // -------- discovery-3: AI dispatch failure passes the candidate THROUGH (skip-and-flag) --------

  @Test
  void filter_aiDispatchThrows_keepsCandidate_notRejected() {
    DiscoveryCandidate c = candidate("https://example.test/r/outage");
    when(aiService.execute(any())).thenThrow(new RuntimeException("AI down"));

    CandidateFilterOutcome outcome = filter.filter(List.of(c), constraints(), USER_ID);

    // skip-and-flag: kept, not dropped, and NOT recorded as a model rejection.
    assertThat(outcome.kept()).containsExactly(c);
    assertThat(outcome.rejected()).isEmpty();
  }

  @Test
  void filter_aiReturnsNull_keepsCandidate_notRejected() {
    DiscoveryCandidate c = candidate("https://example.test/r/null");
    when(aiService.execute(any())).thenReturn(null);

    CandidateFilterOutcome outcome = filter.filter(List.of(c), constraints(), USER_ID);

    assertThat(outcome.kept()).containsExactly(c);
    assertThat(outcome.rejected()).isEmpty();
  }

  @Test
  void filter_oneCandidateThrows_othersStillEvaluated_keptOrRejectedNormally() {
    DiscoveryCandidate kept = candidate("https://example.test/r/relevant");
    DiscoveryCandidate outage = candidate("https://example.test/r/outage");
    DiscoveryCandidate rejected = candidate("https://example.test/r/irrelevant");

    when(aiService.execute(any()))
        .thenReturn(new CandidateFilterResult(true, new BigDecimal("0.95"), "relevant"))
        .thenThrow(new RuntimeException("AI down"))
        .thenReturn(new CandidateFilterResult(false, new BigDecimal("0.10"), "not a recipe"));

    CandidateFilterOutcome outcome =
        filter.filter(List.of(kept, outage, rejected), constraints(), USER_ID);

    // The outage candidate is kept alongside the genuinely-relevant one; only the model-rejected
    // candidate is in the rejected set.
    assertThat(outcome.kept()).containsExactlyInAnyOrder(kept, outage);
    assertThat(outcome.rejected()).hasSize(1);
    assertThat(outcome.rejected().get(0).candidate()).isEqualTo(rejected);
  }

  // -------- discovery-4: genuine model rejections are surfaced for AI_FILTER_REJECTED rows
  // --------

  @Test
  void filter_modelRejectsRelevantFalse_surfacedInRejectedWithReason() {
    DiscoveryCandidate c = candidate("https://example.test/r/irrelevant");
    when(aiService.execute(any()))
        .thenReturn(
            new CandidateFilterResult(false, new BigDecimal("0.20"), "advert, not a recipe"));

    CandidateFilterOutcome outcome = filter.filter(List.of(c), constraints(), USER_ID);

    assertThat(outcome.kept()).isEmpty();
    assertThat(outcome.rejected()).hasSize(1);
    CandidateFilterOutcome.Rejection r = outcome.rejected().get(0);
    assertThat(r.candidate()).isEqualTo(c);
    assertThat(r.reason()).contains("not relevant").contains("advert, not a recipe");
  }

  @Test
  void filter_belowConfidenceFloor_surfacedInRejected() {
    DiscoveryCandidate c = candidate("https://example.test/r/lowconf");
    when(aiService.execute(any()))
        .thenReturn(new CandidateFilterResult(true, new BigDecimal("0.40"), "maybe"));

    CandidateFilterOutcome outcome = filter.filter(List.of(c), constraints(), USER_ID);

    assertThat(outcome.kept()).isEmpty();
    assertThat(outcome.rejected()).hasSize(1);
    assertThat(outcome.rejected().get(0).reason()).contains("below confidence floor");
  }

  @Test
  void filter_relevantAboveFloor_kept() {
    DiscoveryCandidate c = candidate("https://example.test/r/good");
    when(aiService.execute(any()))
        .thenReturn(new CandidateFilterResult(true, new BigDecimal("0.85"), "clear recipe"));

    CandidateFilterOutcome outcome = filter.filter(List.of(c), constraints(), USER_ID);

    assertThat(outcome.kept()).containsExactly(c);
    assertThat(outcome.rejected()).isEmpty();
  }

  @Test
  void filter_emptyInput_keepAll_noDispatch() {
    CandidateFilterOutcome outcome = filter.filter(List.of(), constraints(), USER_ID);

    assertThat(outcome.kept()).isEmpty();
    assertThat(outcome.rejected()).isEmpty();
  }
}
