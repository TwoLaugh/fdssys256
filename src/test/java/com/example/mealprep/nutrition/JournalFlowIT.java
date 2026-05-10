package com.example.mealprep.nutrition;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.nutrition.api.dto.FoodMoodEntryDto;
import com.example.mealprep.nutrition.api.dto.UpsertFoodMoodEntryRequest;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.event.FoodMoodEntryWrittenEvent;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
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

/** HTTP flow over the food/mood journal aggregate. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  JournalFlowIT.JournalEventCaptureConfig.class
})
@ActiveProfiles("test")
class JournalFlowIT {

  private static final LocalDate DAY = LocalDate.parse("2026-05-09");
  private static final Instant LOGGED_AT = Instant.parse("2026-05-09T12:30:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private JournalEventCapture eventCapture;
  @Autowired private NutritionQueryService nutritionQueryService;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_food_mood_journal");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "dana-" + AuthTestData.shortId();
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

  private UpsertFoodMoodEntryRequest req(MealSlot slot, String text, long expectedVersion) {
    return new UpsertFoodMoodEntryRequest(DAY, slot, text, LOGGED_AT, expectedVersion);
  }

  // ---------------- Anonymous ----------------

  @Test
  void getForDay_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/nutrition/journal/2026-05-09")).andExpect(status().isUnauthorized());
  }

  // ---------------- Create ----------------

  @Test
  void post_createsEntry_returns201_withLocationAndEvent() throws Exception {
    AuthedUser user = registerUser();
    UpsertFoodMoodEntryRequest body = req(MealSlot.LUNCH, "felt good after lunch", 0L);

    mvc.perform(
            post("/api/v1/nutrition/journal/2026-05-09")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.userId").value(user.userId().toString()))
        .andExpect(jsonPath("$.mealSlot").value("LUNCH"))
        .andExpect(jsonPath("$.journalEntry").value("felt good after lunch"))
        .andExpect(openApi().isValid(openApiValidator));

    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_food_mood_journal WHERE user_id = ?",
            Long.class,
            user.userId());
    assertThat(count).isEqualTo(1L);

    assertThat(eventCapture.events()).hasSize(1);
    FoodMoodEntryWrittenEvent event = eventCapture.events().get(0);
    assertThat(event.action().name()).isEqualTo("CREATED");
    assertThat(event.userId()).isEqualTo(user.userId());
  }

  @Test
  void post_returns400_whenPathDateMismatch() throws Exception {
    AuthedUser user = registerUser();
    UpsertFoodMoodEntryRequest body = req(MealSlot.LUNCH, "x", 0L);
    // Path date is 2026-05-10, body says 2026-05-09 → 400.
    mvc.perform(
            post("/api/v1/nutrition/journal/2026-05-10")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns400_whenJournalEntryEmpty() throws Exception {
    AuthedUser user = registerUser();
    UpsertFoodMoodEntryRequest body = req(MealSlot.LUNCH, "", 0L);
    mvc.perform(
            post("/api/v1/nutrition/journal/2026-05-09")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns409_whenSlotTiedDuplicate() throws Exception {
    AuthedUser user = registerUser();
    UpsertFoodMoodEntryRequest body = req(MealSlot.LUNCH, "first", 0L);

    mvc.perform(
            post("/api/v1/nutrition/journal/2026-05-09")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated());

    UpsertFoodMoodEntryRequest second = req(MealSlot.LUNCH, "second", 0L);
    mvc.perform(
            post("/api/v1/nutrition/journal/2026-05-09")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(second)))
        .andExpect(status().isConflict());
  }

  @Test
  void post_allowsMultipleNullSlotEntriesPerDay() throws Exception {
    AuthedUser user = registerUser();
    for (int i = 0; i < 3; i++) {
      UpsertFoodMoodEntryRequest body = req(null, "untied #" + i, 0L);
      mvc.perform(
              post("/api/v1/nutrition/journal/2026-05-09")
                  .cookie(user.cookie())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(body)))
          .andExpect(status().isCreated());
    }

    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_food_mood_journal WHERE user_id = ?",
            Long.class,
            user.userId());
    assertThat(count).isEqualTo(3L);
  }

  // ---------------- GET day ----------------

  @Test
  void getForDay_returnsEmpty_whenNoEntries() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(get("/api/v1/nutrition/journal/2026-05-09").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getForDay_returnsAllEntriesAscByLoggedAt() throws Exception {
    AuthedUser user = registerUser();
    // Create one LUNCH (logged at 12:30) and one DINNER (logged at 19:00).
    mvc.perform(
            post("/api/v1/nutrition/journal/2026-05-09")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new UpsertFoodMoodEntryRequest(
                            DAY,
                            MealSlot.DINNER,
                            "dinner",
                            Instant.parse("2026-05-09T19:00:00Z"),
                            0L))))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/v1/nutrition/journal/2026-05-09")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new UpsertFoodMoodEntryRequest(
                            DAY,
                            MealSlot.LUNCH,
                            "lunch",
                            Instant.parse("2026-05-09T12:00:00Z"),
                            0L))))
        .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/nutrition/journal/2026-05-09").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].mealSlot").value("LUNCH"))
        .andExpect(jsonPath("$[1].mealSlot").value("DINNER"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- PUT update ----------------

  @Test
  void put_updatesEntry_bumpsVersion_publishesUpdatedEvent() throws Exception {
    AuthedUser user = registerUser();
    MvcResult create =
        mvc.perform(
                post("/api/v1/nutrition/journal/2026-05-09")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req(MealSlot.LUNCH, "first", 0L))))
            .andExpect(status().isCreated())
            .andReturn();
    FoodMoodEntryDto created =
        objectMapper.readValue(create.getResponse().getContentAsString(), FoodMoodEntryDto.class);
    eventCapture.clear();

    UpsertFoodMoodEntryRequest update = req(MealSlot.LUNCH, "edited", created.optimisticVersion());
    mvc.perform(
            put("/api/v1/nutrition/journal/2026-05-09/entries/" + created.id())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.journalEntry").value("edited"))
        .andExpect(jsonPath("$.optimisticVersion").value(created.optimisticVersion() + 1))
        .andExpect(openApi().isValid(openApiValidator));

    assertThat(eventCapture.events()).hasSize(1);
    assertThat(eventCapture.events().get(0).action().name()).isEqualTo("UPDATED");
  }

  @Test
  void put_returns409_whenStaleExpectedVersion() throws Exception {
    AuthedUser user = registerUser();
    MvcResult create =
        mvc.perform(
                post("/api/v1/nutrition/journal/2026-05-09")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req(MealSlot.LUNCH, "first", 0L))))
            .andExpect(status().isCreated())
            .andReturn();
    FoodMoodEntryDto created =
        objectMapper.readValue(create.getResponse().getContentAsString(), FoodMoodEntryDto.class);

    UpsertFoodMoodEntryRequest stale = req(MealSlot.LUNCH, "edited", 999L);
    mvc.perform(
            put("/api/v1/nutrition/journal/2026-05-09/entries/" + created.id())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(stale)))
        .andExpect(status().isConflict());
  }

  @Test
  void put_returns404_forOtherUsersEntry() throws Exception {
    AuthedUser owner = registerUser();
    MvcResult create =
        mvc.perform(
                post("/api/v1/nutrition/journal/2026-05-09")
                    .cookie(owner.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req(MealSlot.LUNCH, "owner", 0L))))
            .andExpect(status().isCreated())
            .andReturn();
    FoodMoodEntryDto created =
        objectMapper.readValue(create.getResponse().getContentAsString(), FoodMoodEntryDto.class);

    AuthedUser intruder = registerUser();
    mvc.perform(
            put("/api/v1/nutrition/journal/2026-05-09/entries/" + created.id())
                .cookie(intruder.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        req(MealSlot.LUNCH, "intruder", created.optimisticVersion()))))
        .andExpect(status().isNotFound());
  }

  @Test
  void put_returns400_whenPathDateDiffers() throws Exception {
    AuthedUser user = registerUser();
    MvcResult create =
        mvc.perform(
                post("/api/v1/nutrition/journal/2026-05-09")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req(MealSlot.LUNCH, "first", 0L))))
            .andExpect(status().isCreated())
            .andReturn();
    FoodMoodEntryDto created =
        objectMapper.readValue(create.getResponse().getContentAsString(), FoodMoodEntryDto.class);

    // Path is 2026-05-10 but body says 2026-05-09 — controller-level 400.
    mvc.perform(
            put("/api/v1/nutrition/journal/2026-05-10/entries/" + created.id())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        req(MealSlot.LUNCH, "x", created.optimisticVersion()))))
        .andExpect(status().isBadRequest());
  }

  // ---------------- DELETE ----------------

  @Test
  void delete_removesEntry_returns204_publishesDeletedEvent() throws Exception {
    AuthedUser user = registerUser();
    MvcResult create =
        mvc.perform(
                post("/api/v1/nutrition/journal/2026-05-09")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req(MealSlot.LUNCH, "first", 0L))))
            .andExpect(status().isCreated())
            .andReturn();
    FoodMoodEntryDto created =
        objectMapper.readValue(create.getResponse().getContentAsString(), FoodMoodEntryDto.class);
    eventCapture.clear();

    mvc.perform(
            delete("/api/v1/nutrition/journal/2026-05-09/entries/" + created.id())
                .cookie(user.cookie()))
        .andExpect(status().isNoContent());

    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_food_mood_journal WHERE user_id = ?",
            Long.class,
            user.userId());
    assertThat(count).isEqualTo(0L);

    // Second DELETE → 404.
    mvc.perform(
            delete("/api/v1/nutrition/journal/2026-05-09/entries/" + created.id())
                .cookie(user.cookie()))
        .andExpect(status().isNotFound());

    assertThat(eventCapture.events()).hasSize(1);
    assertThat(eventCapture.events().get(0).action().name()).isEqualTo("DELETED");
  }

  @Test
  void delete_returns404_forOtherUsersEntry() throws Exception {
    AuthedUser owner = registerUser();
    MvcResult create =
        mvc.perform(
                post("/api/v1/nutrition/journal/2026-05-09")
                    .cookie(owner.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req(MealSlot.LUNCH, "x", 0L))))
            .andExpect(status().isCreated())
            .andReturn();
    FoodMoodEntryDto created =
        objectMapper.readValue(create.getResponse().getContentAsString(), FoodMoodEntryDto.class);

    AuthedUser intruder = registerUser();
    mvc.perform(
            delete("/api/v1/nutrition/journal/2026-05-09/entries/" + created.id())
                .cookie(intruder.cookie()))
        .andExpect(status().isNotFound());
  }

  // ---------------- Recent (paginated) ----------------

  @Test
  void getRecent_paginatesNewestFirst() throws Exception {
    AuthedUser user = registerUser();
    // Create 5 untied entries with explicit ascending logged_at.
    for (int i = 0; i < 5; i++) {
      Instant t = Instant.parse("2026-05-09T08:00:00Z").plusSeconds(60L * i);
      mvc.perform(
              post("/api/v1/nutrition/journal/2026-05-09")
                  .cookie(user.cookie())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      objectMapper.writeValueAsString(
                          new UpsertFoodMoodEntryRequest(DAY, null, "entry " + i, t, 0L))))
          .andExpect(status().isCreated());
    }

    mvc.perform(
            get("/api/v1/nutrition/journal")
                .cookie(user.cookie())
                .param("page", "0")
                .param("size", "3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(3))
        .andExpect(jsonPath("$.totalElements").value(5))
        .andExpect(jsonPath("$.content[0].journalEntry").value("entry 4"))
        .andExpect(jsonPath("$.content[1].journalEntry").value("entry 3"))
        .andExpect(jsonPath("$.content[2].journalEntry").value("entry 2"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- Cross-module helper ----------------

  @Test
  void getJournalEntriesForFeedbackContext_capsAt20() throws Exception {
    AuthedUser user = registerUser();
    // Create 25 entries; the helper must cap at 20 newest.
    for (int i = 0; i < 25; i++) {
      Instant t = Instant.parse("2026-05-09T08:00:00Z").plusSeconds(60L * i);
      mvc.perform(
              post("/api/v1/nutrition/journal/2026-05-09")
                  .cookie(user.cookie())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      objectMapper.writeValueAsString(
                          new UpsertFoodMoodEntryRequest(DAY, null, "entry " + i, t, 0L))))
          .andExpect(status().isCreated());
    }

    List<FoodMoodEntryDto> top =
        nutritionQueryService.getJournalEntriesForFeedbackContext(user.userId());
    assertThat(top).hasSize(20);
    // Newest first — entry 24 is the most recent.
    assertThat(top.get(0).journalEntry()).isEqualTo("entry 24");
    assertThat(top.get(19).journalEntry()).isEqualTo("entry 5");
  }

  // ---------------- AFTER_COMMIT capture ----------------

  @TestConfiguration
  static class JournalEventCaptureConfig {
    @Bean
    JournalEventCapture journalEventCapture() {
      return new JournalEventCapture();
    }
  }

  static class JournalEventCapture {
    private final List<FoodMoodEntryWrittenEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onWrite(FoodMoodEntryWrittenEvent event) {
      events.add(event);
    }

    public List<FoodMoodEntryWrittenEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
