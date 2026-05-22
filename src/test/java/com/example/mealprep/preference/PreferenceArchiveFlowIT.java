package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.preference.api.dto.PreferenceArchiveEntryDto;
import com.example.mealprep.preference.domain.entity.ArchiveReason;
import com.example.mealprep.preference.domain.repository.PreferenceArchiveRepository;
import com.example.mealprep.preference.domain.service.PreferenceArchiveQueryService;
import com.example.mealprep.preference.domain.service.PreferenceArchiveUpdateService;
import com.example.mealprep.preference.exception.PreferenceArchiveEntryNotFoundException;
import com.example.mealprep.preference.testdata.PreferenceArchiveTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full integration flow for the preference archive against Testcontainers Postgres: register user →
 * archive items via the in-process update service → GET the read endpoints → re-promote → verify
 * the append-only-with-status semantics, JSONB round-trip, pagination, field-prefix filtering, and
 * cross-tenant isolation.
 *
 * <p>OpenAPI contract validation lives in {@code PreferenceArchiveControllerWebTest}; this IT
 * focuses on the data + transaction path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class PreferenceArchiveFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private PreferenceArchiveRepository archiveRepository;
  @Autowired private PreferenceArchiveUpdateService updateService;
  @Autowired private PreferenceArchiveQueryService queryService;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    archiveRepository.deleteAll();
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "archiveUser-" + AuthTestData.shortId();
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

  // ---------------- tests ----------------

  @Test
  void archiveItem_persistsRow_jsonbRoundTrips_andGetReturnsIt() throws Exception {
    AuthedUser user = registerUser();
    PreferenceArchiveEntryDto saved =
        updateService.archiveItem(user.userId(), PreferenceArchiveTestData.archiveRequest());

    // JSONB round-trip: re-read from DB and confirm payload survived verbatim.
    var reread = archiveRepository.findById(saved.id()).orElseThrow();
    assertThat(reread.getItemPayload().get("item").asText())
        .isEqualTo(PreferenceArchiveTestData.DEFAULT_ITEM_KEY);
    assertThat(reread.getItemPayload().get("evidenceCount").asInt()).isEqualTo(4);
    assertThat(reread.getRePromotedAt()).isNull();

    mvc.perform(get("/api/v1/preferences/archive").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(
            jsonPath("$.content[0].itemKey").value(PreferenceArchiveTestData.DEFAULT_ITEM_KEY))
        .andExpect(
            jsonPath("$.content[0].itemPayload.item")
                .value(PreferenceArchiveTestData.DEFAULT_ITEM_KEY))
        .andExpect(jsonPath("$.content[0].archivedReason").value("LOW_EVIDENCE"))
        .andExpect(jsonPath("$.content[0].rePromotedAt").doesNotExist());
  }

  @Test
  void archiveItem_twiceForSameLogicalItem_insertsTwoRows_noUpsert() throws Exception {
    AuthedUser user = registerUser();
    updateService.archiveItem(user.userId(), PreferenceArchiveTestData.archiveRequest());
    updateService.archiveItem(user.userId(), PreferenceArchiveTestData.archiveRequest());

    assertThat(archiveRepository.findAllByUserId(user.userId())).hasSize(2);
  }

  @Test
  void markRePromoted_flipsRePromotedAt_rowRemainsAsHistory() throws Exception {
    AuthedUser user = registerUser();
    updateService.archiveItem(user.userId(), PreferenceArchiveTestData.archiveRequest());

    PreferenceArchiveEntryDto promoted =
        updateService.markRePromoted(
            user.userId(),
            PreferenceArchiveTestData.DEFAULT_FIELD_PATH,
            PreferenceArchiveTestData.DEFAULT_ITEM_KEY);

    assertThat(promoted.rePromotedAt()).isNotNull();
    // Row not deleted — remains as history.
    assertThat(archiveRepository.findAllByUserId(user.userId())).hasSize(1);
    // No longer counted as active.
    assertThat(queryService.countActiveEntries(user.userId())).isZero();
  }

  @Test
  void rePromoteThenArchiveAgain_yieldsTwoRows_oneRePromotedOneActive() throws Exception {
    AuthedUser user = registerUser();
    updateService.archiveItem(user.userId(), PreferenceArchiveTestData.archiveRequest());
    updateService.markRePromoted(
        user.userId(),
        PreferenceArchiveTestData.DEFAULT_FIELD_PATH,
        PreferenceArchiveTestData.DEFAULT_ITEM_KEY);
    // Item gets pruned again later → new row.
    updateService.archiveItem(user.userId(), PreferenceArchiveTestData.archiveRequest());

    assertThat(archiveRepository.findAllByUserId(user.userId())).hasSize(2);
    assertThat(queryService.countActiveEntries(user.userId())).isEqualTo(1L);
    // The currently-archived (unpromoted) instance is found by the predicate query.
    assertThat(
            archiveRepository.findByUserIdAndFieldPathAndItemKeyAndRePromotedAtIsNull(
                user.userId(),
                PreferenceArchiveTestData.DEFAULT_FIELD_PATH,
                PreferenceArchiveTestData.DEFAULT_ITEM_KEY))
        .isPresent();
  }

  @Test
  void markRePromoted_noUnpromotedEntry_throwsNotFound() throws Exception {
    AuthedUser user = registerUser();

    assertThatThrownBy(
            () ->
                updateService.markRePromoted(
                    user.userId(), "ingredientPreferences.favourites", "nope"))
        .isInstanceOf(PreferenceArchiveEntryNotFoundException.class);
  }

  @Test
  void getArchive_returnsNewestFirst() throws Exception {
    AuthedUser user = registerUser();
    updateService.archiveItem(
        user.userId(),
        PreferenceArchiveTestData.archiveRequest(
            "ingredientPreferences.favourites", "older", ArchiveReason.STALE));
    updateService.archiveItem(
        user.userId(),
        PreferenceArchiveTestData.archiveRequest(
            "recipesToRepeat", "newer", ArchiveReason.TOKEN_PRESSURE));

    // newest archivedAt first — "newer" was inserted second so has a later clock instant.
    mvc.perform(get("/api/v1/preferences/archive").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].itemKey").value("newer"))
        .andExpect(jsonPath("$.content[1].itemKey").value("older"));
  }

  @Test
  void getArchive_withFieldPathPrefix_filtersByPrefix() throws Exception {
    AuthedUser user = registerUser();
    updateService.archiveItem(
        user.userId(),
        PreferenceArchiveTestData.archiveRequest(
            "ingredientPreferences.favourites", "kale", ArchiveReason.STALE));
    updateService.archiveItem(
        user.userId(),
        PreferenceArchiveTestData.archiveRequest(
            "recipesToRepeat", "Beef stew", ArchiveReason.TOKEN_PRESSURE));

    mvc.perform(
            get("/api/v1/preferences/archive")
                .param("fieldPathPrefix", "ingredientPreferences")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].itemKey").value("kale"));
  }

  @Test
  void getArchive_emptyArchive_returnsEmptyPage() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(get("/api/v1/preferences/archive").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void activeCount_endpoint_returnsUnpromotedCount() throws Exception {
    AuthedUser user = registerUser();
    updateService.archiveItem(
        user.userId(),
        PreferenceArchiveTestData.archiveRequest("recipesToRepeat", "a", ArchiveReason.STALE));
    updateService.archiveItem(
        user.userId(),
        PreferenceArchiveTestData.archiveRequest("recipesToRepeat", "b", ArchiveReason.STALE));
    updateService.markRePromoted(user.userId(), "recipesToRepeat", "a");

    mvc.perform(get("/api/v1/preferences/archive/active-count").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1));
  }

  @Test
  void crossTenant_userBCannotSeeUserAArchive() throws Exception {
    AuthedUser userA = registerUser();
    AuthedUser userB = registerUser();
    updateService.archiveItem(userA.userId(), PreferenceArchiveTestData.archiveRequest());

    mvc.perform(get("/api/v1/preferences/archive").cookie(userB.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0));
  }

  @Test
  void pagination_respectsPageAndSize() throws Exception {
    AuthedUser user = registerUser();
    for (int i = 0; i < 25; i++) {
      updateService.archiveItem(
          user.userId(),
          PreferenceArchiveTestData.archiveRequest(
              "recipesToRepeat", "item-" + i, ArchiveReason.STALE));
    }

    assertThat(queryService.getArchive(user.userId(), PageRequest.of(0, 20)).getContent())
        .hasSize(20);
    assertThat(queryService.getArchive(user.userId(), PageRequest.of(1, 20)).getContent())
        .hasSize(5);
  }

  @Test
  void noArchiveWriteEndpointExists_postReturnsMethodNotAllowedOrNotFound() throws Exception {
    AuthedUser user = registerUser();

    int statusCode =
        mvc.perform(
                post("/api/v1/preferences/archive")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn()
            .getResponse()
            .getStatus();
    // The archive is read-only via REST — no POST mapping exists.
    assertThat(statusCode).isIn(404, 405);
  }

  /** Sanity: full archive (used by the AI delta task) returns both promoted and unpromoted rows. */
  @Test
  void getFullArchive_returnsPromotedAndUnpromoted() throws Exception {
    AuthedUser user = registerUser();
    updateService.archiveItem(
        user.userId(),
        PreferenceArchiveTestData.archiveRequest("recipesToRepeat", "a", ArchiveReason.STALE));
    updateService.archiveItem(
        user.userId(),
        PreferenceArchiveTestData.archiveRequest("recipesToRepeat", "b", ArchiveReason.STALE));
    updateService.markRePromoted(user.userId(), "recipesToRepeat", "a");

    assertThat(queryService.getFullArchive(user.userId())).hasSize(2);
  }
}
