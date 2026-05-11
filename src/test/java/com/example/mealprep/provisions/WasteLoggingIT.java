package com.example.mealprep.provisions;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.LogWasteRequest;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import com.example.mealprep.provisions.event.ItemAdjustmentSource;
import com.example.mealprep.provisions.event.ItemQuantityAdjustedEvent;
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
 * Behaviour spec for {@code POST /api/v1/provisions/waste}. Covers the four flows:
 *
 * <ul>
 *   <li>QUANTITY-tracked deduction with audit row + {@code ItemQuantityAdjustedEvent}.
 *   <li>Exhaustion → {@code itemStatus = WASTED} + extra audit row.
 *   <li>422 when quantity > remaining stock.
 *   <li>STATUS-tracked lifecycle mark + {@code ItemSpoiledEvent}.
 *   <li>Free-form waste (no linked item) — no inventory mutation, no audit, no inventory event.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  WasteLoggingIT.WasteEventCaptureConfig.class
})
@ActiveProfiles("test")
class WasteLoggingIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private ProvisionUpdateService provisionUpdateService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private WasteEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM provision_waste_log");
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

  // ---------------- happy paths ----------------

  @Test
  void post_quantityTracked_deductsInventory_writesAudit_publishesQuantityEvent() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto inv =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    LogWasteRequest body = ProvisionsTestData.logWasteRequestLinkedQuantity(inv.id());
    mvc.perform(
            post("/api/v1/provisions/waste")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.itemName").value("Cheddar"))
        .andExpect(jsonPath("$.reason").value("EXPIRED"))
        .andExpect(jsonPath("$.inventoryItemId").value(inv.id().toString()))
        .andExpect(openApi().isValid(openApiValidator));

    // Inventory quantity deducted from 250g to 150g.
    Double remaining =
        jdbcTemplate.queryForObject(
            "SELECT quantity FROM provision_inventory WHERE id = ?", Double.class, inv.id());
    assertThat(remaining).isEqualTo(150.0);

    // Audit row for quantity change.
    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit"
                + " WHERE inventory_item_id = ? AND field_changed = 'quantity'",
            Long.class,
            inv.id());
    assertThat(auditCount).isEqualTo(1L);

    assertThat(eventCapture.quantityAdjustedEvents()).hasSize(1);
    ItemQuantityAdjustedEvent ev = eventCapture.quantityAdjustedEvents().get(0);
    assertThat(ev.userId()).isEqualTo(user.userId());
    assertThat(ev.source()).isEqualTo(ItemAdjustmentSource.WASTE);
    assertThat(ev.affectedItemIds()).containsExactly(inv.id());
  }

  @Test
  void post_quantityTracked_exhaustsItem_marksWasted_writesTwoAuditRows() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto inv =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    // Exhaust by wasting the full 250g.
    LogWasteRequest body =
        new LogWasteRequest(
            inv.id(),
            "Cheddar",
            new java.math.BigDecimal("250.000"),
            "g",
            com.example.mealprep.provisions.api.dto.WasteReason.EXPIRED,
            new java.math.BigDecimal("3.49"),
            java.time.LocalDate.parse("2026-05-08"),
            null);

    mvc.perform(
            post("/api/v1/provisions/waste")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated());

    Double remaining =
        jdbcTemplate.queryForObject(
            "SELECT quantity FROM provision_inventory WHERE id = ?", Double.class, inv.id());
    assertThat(remaining).isEqualTo(0.0);

    String itemStatus =
        jdbcTemplate.queryForObject(
            "SELECT item_status FROM provision_inventory WHERE id = ?", String.class, inv.id());
    assertThat(itemStatus).isEqualTo("WASTED");

    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit WHERE inventory_item_id = ?"
                + " AND field_changed IN ('quantity', 'itemStatus')",
            Long.class,
            inv.id());
    assertThat(auditCount).isEqualTo(2L);
  }

  @Test
  void post_quantityTracked_exceedsRemaining_returns422() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto inv =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    LogWasteRequest body =
        new LogWasteRequest(
            inv.id(),
            "Cheddar",
            new java.math.BigDecimal("999.000"),
            "g",
            com.example.mealprep.provisions.api.dto.WasteReason.EXPIRED,
            null,
            java.time.LocalDate.parse("2026-05-08"),
            null);

    mvc.perform(
            post("/api/v1/provisions/waste")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/waste-exceeds-inventory"))
        .andExpect(jsonPath("$.inventoryItemId").value(inv.id().toString()));

    // Inventory unchanged; no waste row persisted.
    Double remaining =
        jdbcTemplate.queryForObject(
            "SELECT quantity FROM provision_inventory WHERE id = ?", Double.class, inv.id());
    assertThat(remaining).isEqualTo(250.0);
    Long wasteRowCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_waste_log WHERE inventory_item_id = ?",
            Long.class,
            inv.id());
    assertThat(wasteRowCount).isEqualTo(0L);
  }

  @Test
  void post_statusTracked_marksWasted_publishesSpoiledEvent_noQuantityEvent() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto inv =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createStatusTrackedRequest(), AuditActor.USER);

    LogWasteRequest body = ProvisionsTestData.logWasteRequestLinkedStatus(inv.id());
    mvc.perform(
            post("/api/v1/provisions/waste")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated());

    String itemStatus =
        jdbcTemplate.queryForObject(
            "SELECT item_status FROM provision_inventory WHERE id = ?", String.class, inv.id());
    assertThat(itemStatus).isEqualTo("WASTED");

    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit"
                + " WHERE inventory_item_id = ? AND field_changed = 'itemStatus'",
            Long.class,
            inv.id());
    assertThat(auditCount).isEqualTo(1L);

    assertThat(eventCapture.spoiledEvents()).isNotEmpty();
    ItemSpoiledEvent spoiled = eventCapture.spoiledEvents().get(0);
    assertThat(spoiled.affectedItemIds()).containsExactly(inv.id());
    assertThat(spoiled.reason()).isEqualTo("wasted");
    assertThat(eventCapture.quantityAdjustedEvents()).isEmpty();
  }

  @Test
  void post_freeForm_noInventoryItem_persistsRowOnly_noInventoryMutation() throws Exception {
    AuthedUser user = registerUser();
    LogWasteRequest body = ProvisionsTestData.logWasteRequestFreeForm();
    mvc.perform(
            post("/api/v1/provisions/waste")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.inventoryItemId").doesNotExist())
        .andExpect(jsonPath("$.itemName").value("Bunch of celery"));

    Long inventoryCount =
        jdbcTemplate.queryForObject("SELECT count(*) FROM provision_inventory", Long.class);
    assertThat(inventoryCount).isEqualTo(0L);
    Long auditCount =
        jdbcTemplate.queryForObject("SELECT count(*) FROM provision_inventory_audit", Long.class);
    assertThat(auditCount).isEqualTo(0L);

    assertThat(eventCapture.quantityAdjustedEvents()).isEmpty();
    assertThat(eventCapture.spoiledEvents()).isEmpty();
  }

  // ---------------- 404 / 401 / validation ----------------

  @Test
  void post_returns401_whenAnonymous() throws Exception {
    LogWasteRequest body = ProvisionsTestData.logWasteRequestFreeForm();
    mvc.perform(
            post("/api/v1/provisions/waste")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void post_returns404_whenLinkedItemOwnedByOtherUser() throws Exception {
    AuthedUser owner = registerUser();
    AuthedUser intruder = registerUser();
    InventoryItemDto inv =
        provisionUpdateService.createInventoryItem(
            owner.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    LogWasteRequest body = ProvisionsTestData.logWasteRequestLinkedQuantity(inv.id());
    mvc.perform(
            post("/api/v1/provisions/waste")
                .cookie(intruder.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/inventory-item-not-found"));
  }

  @Test
  void post_returns400_whenItemNameBlank() throws Exception {
    AuthedUser user = registerUser();
    String json =
        """
        {"itemName":"","reason":"EXPIRED","occurredOn":"2026-05-08"}
        """;
    mvc.perform(
            post("/api/v1/provisions/waste")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns400_whenOccurredOnInFuture() throws Exception {
    AuthedUser user = registerUser();
    String json = "{\"itemName\":\"celery\",\"reason\":\"EXPIRED\",\"occurredOn\":\"2099-12-31\"}";
    mvc.perform(
            post("/api/v1/provisions/waste")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns400_whenQuantityWithoutUnit() throws Exception {
    AuthedUser user = registerUser();
    String json =
        "{\"itemName\":\"celery\",\"quantity\":100,\"reason\":\"EXPIRED\",\"occurredOn\":\"2026-05-08\"}";
    mvc.perform(
            post("/api/v1/provisions/waste")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_repeatedIdenticalBody_persistsTwoRows() throws Exception {
    AuthedUser user = registerUser();
    LogWasteRequest body = ProvisionsTestData.logWasteRequestFreeForm();
    String json = objectMapper.writeValueAsString(body);
    mvc.perform(
            post("/api/v1/provisions/waste")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/v1/provisions/waste")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isCreated());
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_waste_log WHERE user_id = ?",
            Long.class,
            user.userId());
    assertThat(count).isEqualTo(2L);
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class WasteEventCaptureConfig {
    @Bean
    WasteEventCapture wasteEventCapture() {
      return new WasteEventCapture();
    }
  }

  static class WasteEventCapture {
    private final List<ItemQuantityAdjustedEvent> quantityAdjusted = new CopyOnWriteArrayList<>();
    private final List<ItemSpoiledEvent> spoiled = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onQuantityAdjusted(ItemQuantityAdjustedEvent event) {
      quantityAdjusted.add(event);
    }

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onSpoiled(ItemSpoiledEvent event) {
      spoiled.add(event);
    }

    public List<ItemQuantityAdjustedEvent> quantityAdjustedEvents() {
      return quantityAdjusted;
    }

    public List<ItemSpoiledEvent> spoiledEvents() {
      return spoiled;
    }

    public void clear() {
      quantityAdjusted.clear();
      spoiled.clear();
    }
  }
}
