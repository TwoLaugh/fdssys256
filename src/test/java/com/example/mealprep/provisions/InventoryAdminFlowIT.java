package com.example.mealprep.provisions;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import com.example.mealprep.provisions.event.ItemRanOutEvent;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import com.example.mealprep.provisions.testdata.ProvisionsTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
 * Full HTTP flow over the inventory admin endpoints (mark-spoiled, mark-exhausted, soft-delete,
 * audit-log GET). Covers idempotency, ownership-404, audit-row + AFTER_COMMIT event semantics.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  InventoryAdminFlowIT.AdminEventCaptureConfig.class
})
@ActiveProfiles("test")
class InventoryAdminFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private ProvisionUpdateService provisionUpdateService;
  @Autowired private AdminEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM provision_inventory_audit");
    jdbcTemplate.update("DELETE FROM provision_inventory");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
  }

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

  // ---------------- mark-spoiled ----------------

  @Test
  void markSpoiled_returns200_writesAudit_andPublishesEvent() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    mvc.perform(
            post("/api/v1/provisions/inventory/" + created.id() + "/mark-spoiled")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.itemStatus").value("SPOILED"))
        .andExpect(openApi().isValid(openApiValidator));

    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit"
                + " WHERE inventory_item_id = ? AND field_changed = 'itemStatus'",
            Long.class,
            created.id());
    assertThat(auditCount).isEqualTo(1L);

    assertThat(eventCapture.spoiledEvents()).hasSize(1);
    assertThat(eventCapture.spoiledEvents().get(0).reason()).isEqualTo("user_marked");
    assertThat(eventCapture.spoiledEvents().get(0).affectedItemIds()).containsExactly(created.id());
  }

  @Test
  void markSpoiled_isIdempotent_onAlreadySpoiledRow() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    mvc.perform(
            post("/api/v1/provisions/inventory/" + created.id() + "/mark-spoiled")
                .cookie(user.cookie()))
        .andExpect(status().isOk());
    eventCapture.clear();

    mvc.perform(
            post("/api/v1/provisions/inventory/" + created.id() + "/mark-spoiled")
                .cookie(user.cookie()))
        .andExpect(status().isOk());

    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit"
                + " WHERE inventory_item_id = ? AND field_changed = 'itemStatus'",
            Long.class,
            created.id());
    assertThat(auditCount).isEqualTo(1L);
    assertThat(eventCapture.spoiledEvents()).isEmpty();
  }

  @Test
  void markSpoiled_returns404_whenOwnedByOtherUser() throws Exception {
    AuthedUser owner = registerUser();
    AuthedUser intruder = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            owner.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    mvc.perform(
            post("/api/v1/provisions/inventory/" + created.id() + "/mark-spoiled")
                .cookie(intruder.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  // ---------------- mark-exhausted ----------------

  @Test
  void markExhausted_returns200_andPublishesItemRanOutEvent() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createStatusTrackedRequest(), AuditActor.USER);

    mvc.perform(
            post("/api/v1/provisions/inventory/" + created.id() + "/mark-exhausted")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.itemStatus").value("EXHAUSTED"));

    assertThat(eventCapture.ranOutEvents()).hasSize(1);
    ItemRanOutEvent event = eventCapture.ranOutEvents().get(0);
    assertThat(event.wasStaple()).isTrue();
    assertThat(event.affectedItemIds()).containsExactly(created.id());
  }

  // ---------------- DELETE soft-delete ----------------

  @Test
  void delete_returns204_setsItemStatusWasted_andDoesNotShowInList() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    mvc.perform(delete("/api/v1/provisions/inventory/" + created.id()).cookie(user.cookie()))
        .andExpect(status().isNoContent());

    String dbStatus =
        jdbcTemplate.queryForObject(
            "SELECT item_status FROM provision_inventory WHERE id = ?", String.class, created.id());
    assertThat(dbStatus).isEqualTo("WASTED");

    mvc.perform(get("/api/v1/provisions/inventory").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0));
  }

  @Test
  void delete_isIdempotent_onAlreadyWastedRow() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    mvc.perform(delete("/api/v1/provisions/inventory/" + created.id()).cookie(user.cookie()))
        .andExpect(status().isNoContent());
    Long auditAfterFirst =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit WHERE inventory_item_id = ?"
                + " AND field_changed = 'itemStatus'",
            Long.class,
            created.id());

    mvc.perform(delete("/api/v1/provisions/inventory/" + created.id()).cookie(user.cookie()))
        .andExpect(status().isNoContent());
    Long auditAfterSecond =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit WHERE inventory_item_id = ?"
                + " AND field_changed = 'itemStatus'",
            Long.class,
            created.id());

    assertThat(auditAfterSecond).isEqualTo(auditAfterFirst);
  }

  @Test
  void delete_returns404_whenOwnedByOtherUser() throws Exception {
    AuthedUser owner = registerUser();
    AuthedUser intruder = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            owner.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    mvc.perform(delete("/api/v1/provisions/inventory/" + created.id()).cookie(intruder.cookie()))
        .andExpect(status().isNotFound());
  }

  // ---------------- audit-log GET ----------------

  @Test
  void auditLog_returnsNewestFirst_withDefaultSize20() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);
    provisionUpdateService.markSpoiled(created.id(), user.userId());

    mvc.perform(
            get("/api/v1/provisions/inventory/" + created.id() + "/audit-log")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.content.length()").value(2))
        // newest first → mark-spoiled (itemStatus) then create
        .andExpect(jsonPath("$.content[0].fieldChanged").value("itemStatus"))
        .andExpect(jsonPath("$.content[1].fieldChanged").value("created"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void auditLog_returns404_whenOwnedByOtherUser() throws Exception {
    AuthedUser owner = registerUser();
    AuthedUser intruder = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            owner.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    mvc.perform(
            get("/api/v1/provisions/inventory/" + created.id() + "/audit-log")
                .cookie(intruder.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void auditLog_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/provisions/inventory/" + UUID.randomUUID() + "/audit-log"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void auditLog_clampsSizeAbove100ToMax() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    mvc.perform(
            get("/api/v1/provisions/inventory/" + created.id() + "/audit-log?size=500")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(100));
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class AdminEventCaptureConfig {
    @Bean
    AdminEventCapture adminEventCapture() {
      return new AdminEventCapture();
    }
  }

  static class AdminEventCapture {
    private final List<ItemSpoiledEvent> spoiled = new CopyOnWriteArrayList<>();
    private final List<ItemRanOutEvent> ranOut = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onSpoiled(ItemSpoiledEvent event) {
      spoiled.add(event);
    }

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onRanOut(ItemRanOutEvent event) {
      ranOut.add(event);
    }

    public List<ItemSpoiledEvent> spoiledEvents() {
      return spoiled;
    }

    public List<ItemRanOutEvent> ranOutEvents() {
      return ranOut;
    }

    public void clear() {
      spoiled.clear();
      ranOut.clear();
    }
  }
}
