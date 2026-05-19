package com.example.mealprep.nutrition;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.nutrition.api.dto.IntakeEntryDto;
import com.example.mealprep.nutrition.api.dto.OverrideIntakeRequest;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.event.IntakeLoggedEvent;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full HTTP slot-lifecycle flow over {@code IntakeController}. The day + plan slots are seeded via
 * {@link NutritionUpdateService#prefillFromPlan} (no public prefill endpoint exists in 01b — the
 * planner module owns that call), then every slot mutation is driven over the real HTTP cycle
 * through MockMvc: {@code confirm / override / edit / skip}, the per-day read, the paginated
 * audit-log, and the weekly aggregate.
 *
 * <p>This complements the existing {@code IntakeFlowIT} (snack-only HTTP) and {@code
 * PrefillFromPlanIT} (in-process service calls, no controller / mapper / exception-handler / JSON
 * round-trip). Exercising the slot endpoints over HTTP is the genuine integration gap: it drives
 * {@code IntakeController}'s slot handlers, {@code IntakeMapper#toSlotDto} / {@code
 * toAuditEntryDto}, the {@code @Transactional} {@code NutritionServiceImpl} slot methods, and the
 * {@code NutritionExceptionHandler} mappings for slot/day-not-found over the wire.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  IntakeLifecycleFlowIT.IntakeEventCaptureConfig.class
})
@ActiveProfiles("test")
class IntakeLifecycleFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private NutritionUpdateService updateService;
  @Autowired private IntakeEventCapture eventCapture;

  // Anchor every fixture date to a Monday relative to wall-clock so a slot/day date compared to
  // LocalDate.now() (divergence detector, weekly-window maths) never time-bombs.
  private static final LocalDate WEEK_START =
      LocalDate.now().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
  private static final LocalDate DAY = WEEK_START.plusDays(2); // Wednesday

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_intake_audit");
    jdbcTemplate.update("DELETE FROM nutrition_intake_snack");
    jdbcTemplate.update("DELETE FROM nutrition_intake_slot");
    jdbcTemplate.update("DELETE FROM nutrition_intake_day");
    jdbcTemplate.update("DELETE FROM nutrition_divergence_state");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
  }

  // ---------------- helpers ----------------

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "alice-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    String userIdJson =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
    return new AuthedUser(UUID.fromString(userIdJson), cookie);
  }

  /** Seed a prefilled day (BREAKFAST/LUNCH/DINNER plan slots) for the authed user via the SPI. */
  private void seedPrefilledDay(UUID userId) {
    updateService.prefillFromPlan(
        userId, DAY, UUID.randomUUID(), NutritionTestData.defaultPlannedSlots());
  }

  // ---------------- POST /{date}/slots/{slot}/confirm ----------------

  @Test
  void confirm_returns401_whenAnonymous() throws Exception {
    mvc.perform(post("/api/v1/nutrition/intake/" + DAY + "/slots/BREAKFAST/confirm"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void confirm_returns404_whenDayMissing() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(
            post("/api/v1/nutrition/intake/" + DAY + "/slots/BREAKFAST/confirm")
                .cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/intake-day-not-found"));
  }

  @Test
  void confirm_returns404_whenSlotNotPrefilled() throws Exception {
    AuthedUser user = registerUser();
    seedPrefilledDay(user.userId());

    // SNACKS was not part of the prefilled plan slots.
    mvc.perform(
            post("/api/v1/nutrition/intake/" + DAY + "/slots/SNACKS/confirm").cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/intake-slot-not-found"));
  }

  @Test
  void confirm_copiesPlannedIntoActual_writesAudit_publishesEvent() throws Exception {
    AuthedUser user = registerUser();
    seedPrefilledDay(user.userId());
    eventCapture.clear();

    mvc.perform(
            post("/api/v1/nutrition/intake/" + DAY + "/slots/BREAKFAST/confirm")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slots.length()").value(3))
        // mapSlots sorts by mealSlot ordinal: index 0 = BREAKFAST.
        .andExpect(jsonPath("$.slots[0].mealSlot").value("BREAKFAST"))
        .andExpect(jsonPath("$.slots[0].actual.status").value("CONFIRMED"))
        // planned breakfast = 500 kcal / 30 g protein (see NutritionTestData.defaultPlannedSlots).
        .andExpect(jsonPath("$.slots[0].actual.calories").value(500))
        .andExpect(jsonPath("$.slots[0].planned.calories").value(500))
        .andExpect(openApi().isValid(openApiValidator));

    Long confirmAudit =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_intake_audit WHERE action = 'CONFIRM'", Long.class);
    assertThat(confirmAudit).isEqualTo(1L);

    assertThat(eventCapture.events()).hasSize(1);
    IntakeLoggedEvent ev = eventCapture.events().get(0);
    assertThat(ev.userId()).isEqualTo(user.userId());
    assertThat(ev.action().name()).isEqualTo("CONFIRM");
  }

  @Test
  void confirm_isIdempotent_secondCallWritesNoExtraAudit() throws Exception {
    AuthedUser user = registerUser();
    seedPrefilledDay(user.userId());

    mvc.perform(
            post("/api/v1/nutrition/intake/" + DAY + "/slots/LUNCH/confirm").cookie(user.cookie()))
        .andExpect(status().isOk());
    eventCapture.clear();

    mvc.perform(
            post("/api/v1/nutrition/intake/" + DAY + "/slots/LUNCH/confirm").cookie(user.cookie()))
        .andExpect(status().isOk())
        // index 1 = LUNCH after ordinal sort.
        .andExpect(jsonPath("$.slots[1].mealSlot").value("LUNCH"))
        .andExpect(jsonPath("$.slots[1].actual.status").value("CONFIRMED"));

    Long confirmAudit =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_intake_audit WHERE action = 'CONFIRM'", Long.class);
    assertThat(confirmAudit).isEqualTo(1L);
    assertThat(eventCapture.events()).isEmpty();
  }

  // ---------------- POST /{date}/slots/{slot}/override ----------------

  @Test
  void override_zeroesActuals_setsNeedsAiParse_overWire() throws Exception {
    AuthedUser user = registerUser();
    seedPrefilledDay(user.userId());

    OverrideIntakeRequest req = new OverrideIntakeRequest("ate out with friends");
    mvc.perform(
            post("/api/v1/nutrition/intake/" + DAY + "/slots/DINNER/override")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        // index 2 = DINNER after ordinal sort.
        .andExpect(jsonPath("$.slots[2].mealSlot").value("DINNER"))
        .andExpect(jsonPath("$.slots[2].actual.status").value("OVERRIDDEN"))
        .andExpect(jsonPath("$.slots[2].actual.needsAiParse").value(true))
        .andExpect(jsonPath("$.slots[2].actual.calories").value(0))
        .andExpect(jsonPath("$.slots[2].actual.overrideFreeText").value("ate out with friends"))
        .andExpect(openApi().isValid(openApiValidator));

    Long overrideAudit =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_intake_audit WHERE action = 'OVERRIDE'", Long.class);
    assertThat(overrideAudit).isEqualTo(1L);
  }

  @Test
  void override_returns400_whenFreeTextBlank() throws Exception {
    AuthedUser user = registerUser();
    seedPrefilledDay(user.userId());

    mvc.perform(
            post("/api/v1/nutrition/intake/" + DAY + "/slots/DINNER/override")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"freeText\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  // ---------------- POST /{date}/slots/{slot}/edit ----------------

  @Test
  void edit_setsStatusEdited_andValuesPersisted_overWire() throws Exception {
    AuthedUser user = registerUser();
    seedPrefilledDay(user.userId());

    IntakeEntryDto entry =
        new IntakeEntryDto(
            420,
            BigDecimal.valueOf(26.0),
            BigDecimal.valueOf(52.0),
            BigDecimal.valueOf(12.0),
            BigDecimal.valueOf(6.0),
            null);
    mvc.perform(
            post("/api/v1/nutrition/intake/" + DAY + "/slots/LUNCH/edit")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry)))
        .andExpect(status().isOk())
        // index 1 = LUNCH after ordinal sort.
        .andExpect(jsonPath("$.slots[1].mealSlot").value("LUNCH"))
        .andExpect(jsonPath("$.slots[1].actual.status").value("EDITED"))
        .andExpect(jsonPath("$.slots[1].actual.calories").value(420))
        .andExpect(jsonPath("$.slots[1].actual.proteinG").value(26.0))
        .andExpect(openApi().isValid(openApiValidator));

    Long editAudit =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_intake_audit WHERE action = 'EDIT'", Long.class);
    assertThat(editAudit).isEqualTo(1L);
  }

  @Test
  void edit_returns400_whenRequiredFieldsMissing() throws Exception {
    AuthedUser user = registerUser();
    seedPrefilledDay(user.userId());

    // calories is @NotNull — omit it.
    mvc.perform(
            post("/api/v1/nutrition/intake/" + DAY + "/slots/LUNCH/edit")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"proteinG\":10,\"carbsG\":10,\"fatG\":5}"))
        .andExpect(status().isBadRequest());
  }

  // ---------------- POST /{date}/slots/{slot}/skip ----------------

  @Test
  void skip_zeroesActuals_setsSkipped_overWire() throws Exception {
    AuthedUser user = registerUser();
    seedPrefilledDay(user.userId());

    mvc.perform(
            post("/api/v1/nutrition/intake/" + DAY + "/slots/BREAKFAST/skip").cookie(user.cookie()))
        .andExpect(status().isOk())
        // index 0 = BREAKFAST after ordinal sort.
        .andExpect(jsonPath("$.slots[0].mealSlot").value("BREAKFAST"))
        .andExpect(jsonPath("$.slots[0].actual.status").value("SKIPPED"))
        .andExpect(jsonPath("$.slots[0].actual.calories").value(0))
        .andExpect(openApi().isValid(openApiValidator));

    Long skipAudit =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_intake_audit WHERE action = 'SKIP'", Long.class);
    assertThat(skipAudit).isEqualTo(1L);
  }

  // ---------------- GET /{date} reflects slot mutations + GET audit-log ----------------

  @Test
  void getDay_returnsMappedSlots_afterMixedMutations() throws Exception {
    AuthedUser user = registerUser();
    seedPrefilledDay(user.userId());

    mvc.perform(
            post("/api/v1/nutrition/intake/" + DAY + "/slots/BREAKFAST/confirm")
                .cookie(user.cookie()))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/nutrition/intake/" + DAY + "/slots/DINNER/skip").cookie(user.cookie()))
        .andExpect(status().isOk());

    // The per-day read exercises IntakeMapper#toDto -> mapSlots -> toSlotDto for a populated day,
    // with slots sorted by mealSlot ordinal (BREAKFAST < LUNCH < DINNER).
    mvc.perform(get("/api/v1/nutrition/intake/" + DAY).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slots.length()").value(3))
        .andExpect(jsonPath("$.slots[0].mealSlot").value("BREAKFAST"))
        .andExpect(jsonPath("$.slots[0].actual.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.slots[1].mealSlot").value("LUNCH"))
        .andExpect(jsonPath("$.slots[1].actual.status").value("PENDING"))
        .andExpect(jsonPath("$.slots[2].mealSlot").value("DINNER"))
        .andExpect(jsonPath("$.slots[2].actual.status").value("SKIPPED"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  /**
   * KNOWN PRODUCTION BUG (documented, not fixed — out of scope for this test-only PR).
   *
   * <p>{@code GET /api/v1/nutrition/intake/{date}/audit-log} is non-functional: it returns HTTP 500
   * (problem type {@code internal-error}) instead of the paginated audit log. Root cause: {@link
   * com.example.mealprep.nutrition.domain.repository.IntakeAuditRepository#findByIntakeDayIdOrderByOccurredAtDesc}
   * declares a {@code Page} return, but {@code IntakeAuditLog} has no scalar {@code intakeDayId}
   * property — only a {@code @ManyToOne(fetch = LAZY) IntakeDay intakeDay} association
   * ({@code @JoinColumn intake_day_id}). Spring Data must auto-derive a <em>count query</em> for
   * the {@code Page} return; deriving it from the {@code IntakeDayId} association traversal
   * combined with the explicit {@code OrderByOccurredAtDesc} clause fails at query-construction
   * time, surfacing as {@code org.springframework.dao.InvalidDataAccessApiUsageException} (no SQL
   * is ever emitted). The slot writes (CONFIRM/SKIP) themselves succeed and persist correctly —
   * only the audit-log read is broken. No existing test exercised this endpoint, so the defect was
   * latent.
   *
   * <p>This test pins the current (buggy) observable behaviour so the regression is captured and a
   * future prod fix flips it to the asserted-correct expectations noted inline.
   */
  @Test
  void auditLog_returns500_knownProdBug_repositoryCountQueryDerivationFails() throws Exception {
    AuthedUser user = registerUser();
    seedPrefilledDay(user.userId());

    // Slot writes succeed and persist (proves the 500 below is isolated to the audit-log read).
    mvc.perform(
            post("/api/v1/nutrition/intake/" + DAY + "/slots/BREAKFAST/confirm")
                .cookie(user.cookie()))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/nutrition/intake/" + DAY + "/slots/LUNCH/skip").cookie(user.cookie()))
        .andExpect(status().isOk());
    Long auditRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_intake_audit", Long.class); // PREFILL + CONFIRM + SKIP
    assertThat(auditRows).isEqualTo(3L);

    // BUG: should be 200 with a 3-element page; current prod behaviour is 500 internal-error.
    // Once the repository is fixed, replace the block below with:
    //   .andExpect(status().isOk())
    //   .andExpect(jsonPath("$.content.length()").value(3))
    //   .andExpect(jsonPath("$.totalElements").value(3))
    mvc.perform(
            get("/api/v1/nutrition/intake/" + DAY + "/audit-log")
                .cookie(user.cookie())
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/internal-error"));
  }

  @Test
  void auditLog_returns400_whenPageNegative() throws Exception {
    AuthedUser user = registerUser();
    seedPrefilledDay(user.userId());

    mvc.perform(
            get("/api/v1/nutrition/intake/" + DAY + "/audit-log")
                .cookie(user.cookie())
                .param("page", "-1"))
        .andExpect(status().isBadRequest());
  }

  // ---------------- GET /week/{weekStart}/aggregate reflecting slot actuals ----------------

  @Test
  void weeklyAggregate_reflectsConfirmedSlotActuals() throws Exception {
    AuthedUser user = registerUser();
    seedPrefilledDay(user.userId());

    // Confirm all three plan slots: actuals = 500 + 600 + 700 = 1800 kcal on the Wednesday.
    for (MealSlot slot : List.of(MealSlot.BREAKFAST, MealSlot.LUNCH, MealSlot.DINNER)) {
      mvc.perform(
              post("/api/v1/nutrition/intake/" + DAY + "/slots/" + slot + "/confirm")
                  .cookie(user.cookie()))
          .andExpect(status().isOk());
    }

    // Wednesday is index 2 (Mon=0) within the week-aggregate perDay array.
    mvc.perform(
            get("/api/v1/nutrition/intake/week/" + WEEK_START + "/aggregate").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.weekStart").value(WEEK_START.toString()))
        .andExpect(jsonPath("$.perDay.length()").value(7))
        .andExpect(jsonPath("$.perDay[2].caloriesActualSoFar").value(1800))
        .andExpect(jsonPath("$.weeklyTotal.caloriesActualSoFar").value(1800))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class IntakeEventCaptureConfig {
    @Bean
    IntakeEventCapture intakeLifecycleEventCapture() {
      return new IntakeEventCapture();
    }
  }

  static class IntakeEventCapture {
    private final List<IntakeLoggedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onIntakeLogged(IntakeLoggedEvent event) {
      events.add(event);
    }

    public List<IntakeLoggedEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
