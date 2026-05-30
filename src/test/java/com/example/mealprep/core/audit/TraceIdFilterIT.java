package com.example.mealprep.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.core.audit.domain.service.internal.TraceContext;
import com.example.mealprep.core.audit.trace.TraceIdFilter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web-slice integration test for {@link TraceIdFilter}. Asserts the MDC seed (inbound header
 * reused, missing header generated), the response-header echo, and post-request MDC cleanliness. A
 * stub controller captures the trace id observed by application code <em>during</em> the request.
 * Per lld/core.md §Test Plan.
 */
@WebMvcTest(
    controllers = TraceIdFilterIT.TraceProbeController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
@Import({TraceIdFilter.class, TraceIdFilterIT.TraceProbeController.class})
class TraceIdFilterIT {

  @Autowired private MockMvc mvc;
  @Autowired private TraceProbeController probe;

  @Test
  void inboundTraceId_isReused_propagatedToMdc_andEchoedOnResponse() throws Exception {
    UUID inbound = UUID.randomUUID();

    mvc.perform(get("/__trace-probe").header(TraceIdFilter.TRACE_HEADER, inbound.toString()))
        .andExpect(status().isOk())
        .andExpect(header().string(TraceIdFilter.TRACE_HEADER, inbound.toString()));

    assertThat(probe.observedTraceId)
        .as("application code saw the inbound trace id via MDC")
        .isEqualTo(inbound.toString());
  }

  @Test
  void missingTraceId_isGenerated_propagatedToMdc_andEchoedOnResponse() throws Exception {
    var result =
        mvc.perform(get("/__trace-probe"))
            .andExpect(status().isOk())
            .andExpect(header().exists(TraceIdFilter.TRACE_HEADER))
            .andReturn();

    String echoed = result.getResponse().getHeader(TraceIdFilter.TRACE_HEADER);
    assertThat(echoed).isNotNull();
    // A well-formed generated UUID, and the same value application code observed.
    assertThat(UUID.fromString(echoed)).isNotNull();
    assertThat(probe.observedTraceId).isEqualTo(echoed);
  }

  @Test
  void malformedTraceId_isReplacedWithAGeneratedUuid() throws Exception {
    var result =
        mvc.perform(get("/__trace-probe").header(TraceIdFilter.TRACE_HEADER, "not-a-uuid"))
            .andExpect(status().isOk())
            .andReturn();

    String echoed = result.getResponse().getHeader(TraceIdFilter.TRACE_HEADER);
    assertThat(echoed).isNotEqualTo("not-a-uuid");
    assertThat(UUID.fromString(echoed)).isNotNull();
  }

  @Test
  void mdcIsClean_afterRequestCompletes() throws Exception {
    mvc.perform(
            get("/__trace-probe").header(TraceIdFilter.TRACE_HEADER, UUID.randomUUID().toString()))
        .andExpect(status().isOk());

    // The filter clears MDC in finally; the test thread (which MockMvc runs the filter on) must
    // observe no leakage — a second request would otherwise inherit a stale id.
    assertThat(TraceContext.currentTraceId())
        .as("MDC cleared on the request thread after completion")
        .isNull();
  }

  /** Captures the trace id visible to application code during the request. */
  @RestController
  static class TraceProbeController {
    volatile String observedTraceId;

    @GetMapping("/__trace-probe")
    String probe() {
      var id = TraceContext.currentTraceId();
      observedTraceId = id == null ? null : id.toString();
      return "ok";
    }
  }
}
