package com.example.mealprep.provisions;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.example.mealprep.provisions.api.dto.GroceryOrderImportCommand;
import com.example.mealprep.provisions.api.dto.GroceryOrderLine;
import com.example.mealprep.provisions.event.ItemAddedFromGroceryEvent;
import com.example.mealprep.provisions.testdata.ProvisionsTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
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

/**
 * Full HTTP flow over the grocery-import endpoint (01h). Registers a user, exercises the happy-path
 * import + idempotency (409) + validation (400) + auth (401), and asserts (a) audit rows, (b) the
 * single coalesced {@link ItemAddedFromGroceryEvent} per import. OpenAPI contract is checked via
 * swagger-request-validator.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  GroceryImportFlowIT.GroceryImportEventCaptureConfig.class
})
@ActiveProfiles("test")
class GroceryImportFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private GroceryImportEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM provision_grocery_import_log");
    jdbcTemplate.update("DELETE FROM provision_inventory_audit");
    jdbcTemplate.update("DELETE FROM provision_inventory");
    jdbcTemplate.update("DELETE FROM provision_supplier_products");
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

  // ---------------- happy path ----------------

  @Test
  void post_returns200_addsInventory_publishesSingleAddedEvent() throws Exception {
    AuthedUser user = registerUser();
    GroceryOrderImportCommand cmd =
        ProvisionsTestData.groceryOrderImportCommand("tesco", "order-1");

    mvc.perform(
            post("/api/v1/provisions/grocery-import")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cmd)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.addedItems.length()").value(1))
        .andExpect(jsonPath("$.mergedItems.length()").value(0))
        .andExpect(jsonPath("$.updatedSupplierProducts.length()").value(1))
        .andExpect(openApi().isValid(openApiValidator));

    // Idempotency log row inserted.
    Long logCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_grocery_import_log WHERE user_id = ?",
            Long.class,
            user.userId());
    assertThat(logCount).isEqualTo(1L);

    // Audit row written per inventory write, actor = GROCERY_IMPORT.
    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit WHERE user_id = ? AND actor = ?",
            Long.class,
            user.userId(),
            "GROCERY_IMPORT");
    assertThat(auditCount).isEqualTo(1L);

    // Exactly one coalesced ItemAddedFromGroceryEvent per import.
    assertThat(eventCapture.events()).hasSize(1);
    ItemAddedFromGroceryEvent ev = eventCapture.events().get(0);
    assertThat(ev.userId()).isEqualTo(user.userId());
    assertThat(ev.supplier()).isEqualTo("tesco");
    assertThat(ev.orderRef()).isEqualTo("order-1");
    assertThat(ev.affectedItemIds()).hasSize(1);
  }

  // ---------------- idempotency ----------------

  @Test
  void post_returns409_onDuplicateOrderRef() throws Exception {
    AuthedUser user = registerUser();
    GroceryOrderImportCommand cmd =
        ProvisionsTestData.groceryOrderImportCommand("tesco", "order-dup");

    mvc.perform(
            post("/api/v1/provisions/grocery-import")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cmd)))
        .andExpect(status().isOk());

    mvc.perform(
            post("/api/v1/provisions/grocery-import")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cmd)))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/duplicate-grocery-import"));
  }

  // ---------------- two lines, same key + null expiry → merges into one row ----------------

  @Test
  void post_twoLinesSameKeyAndExpiry_mergesIntoOneRow() throws Exception {
    AuthedUser user = registerUser();
    GroceryOrderLine line1 =
        ProvisionsTestData.groceryOrderLine(
            "tesco:cheese-a", "cheese:cheddar", "dairy", new BigDecimal("100.000"));
    GroceryOrderLine line2 =
        ProvisionsTestData.groceryOrderLine(
            "tesco:cheese-b", "cheese:cheddar", "dairy", new BigDecimal("50.000"));
    GroceryOrderImportCommand cmd =
        new GroceryOrderImportCommand(
            "tesco",
            "order-merge",
            LocalDate.parse("2026-05-10"),
            List.of(line1, line2),
            null,
            null);

    mvc.perform(
            post("/api/v1/provisions/grocery-import")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cmd)))
        .andExpect(status().isOk());

    // Only one inventory row for this user + mapping key.
    Long rowCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory"
                + " WHERE user_id = ? AND ingredient_mapping_key = ?",
            Long.class,
            user.userId(),
            "cheese:cheddar");
    assertThat(rowCount).isEqualTo(1L);

    Double quantity =
        jdbcTemplate.queryForObject(
            "SELECT quantity FROM provision_inventory"
                + " WHERE user_id = ? AND ingredient_mapping_key = ?",
            Double.class,
            user.userId(),
            "cheese:cheddar");
    assertThat(quantity).isEqualTo(150.0);
  }

  // ---------------- core-03: case/whitespace-variant keys normalise + merge into one row --------

  @Test
  void post_mixedCaseAndWhitespaceVariantKeys_normaliseAndMergeIntoOneRow() throws Exception {
    AuthedUser user = registerUser();
    // Two lines whose mapping keys differ ONLY by case + whitespace must normalise to the same
    // "chicken breast" key and merge into a single inventory row (no duplicate).
    GroceryOrderLine line1 =
        ProvisionsTestData.groceryOrderLine(
            "tesco:chicken-a", "Chicken Breast", "fresh", new BigDecimal("300.000"));
    GroceryOrderLine line2 =
        ProvisionsTestData.groceryOrderLine(
            "tesco:chicken-b", "  chicken   breast ", "fresh", new BigDecimal("200.000"));
    GroceryOrderImportCommand cmd =
        new GroceryOrderImportCommand(
            "tesco",
            "order-mixedcase-merge",
            LocalDate.parse("2026-05-10"),
            List.of(line1, line2),
            null,
            null);

    mvc.perform(
            post("/api/v1/provisions/grocery-import")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cmd)))
        .andExpect(status().isOk());

    // Exactly one inventory row, stored under the NORMALISED key.
    Long rowCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory"
                + " WHERE user_id = ? AND ingredient_mapping_key = ?",
            Long.class,
            user.userId(),
            "chicken breast");
    assertThat(rowCount).isEqualTo(1L);

    // No row leaked under either raw form.
    Long rawRowCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory"
                + " WHERE user_id = ? AND ingredient_mapping_key IN (?, ?)",
            Long.class,
            user.userId(),
            "Chicken Breast",
            "  chicken   breast ");
    assertThat(rawRowCount).isZero();

    Double quantity =
        jdbcTemplate.queryForObject(
            "SELECT quantity FROM provision_inventory"
                + " WHERE user_id = ? AND ingredient_mapping_key = ?",
            Double.class,
            user.userId(),
            "chicken breast");
    assertThat(quantity).isEqualTo(500.0);
  }

  // ---------------- validation / auth ----------------

  @Test
  void post_returns401_whenAnonymous() throws Exception {
    GroceryOrderImportCommand cmd =
        ProvisionsTestData.groceryOrderImportCommand("tesco", "order-anon");
    mvc.perform(
            post("/api/v1/provisions/grocery-import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cmd)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void post_returns400_whenLinesEmpty() throws Exception {
    AuthedUser user = registerUser();
    String json =
        """
        {
          "supplier": "tesco",
          "orderRef": "order-empty",
          "deliveredOn": "2026-05-10",
          "lines": []
        }
        """;
    mvc.perform(
            post("/api/v1/provisions/grocery-import")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void post_returns400_whenSupplierBlank() throws Exception {
    AuthedUser user = registerUser();
    String json =
        """
        {
          "supplier": "",
          "orderRef": "order-blank",
          "deliveredOn": "2026-05-10",
          "lines": [
            { "productId": "tesco:p", "name": "P", "quantity": 100, "unit": "g" }
          ]
        }
        """;
    mvc.perform(
            post("/api/v1/provisions/grocery-import")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @TestConfiguration
  static class GroceryImportEventCaptureConfig {
    @Bean
    GroceryImportEventCapture groceryImportEventCapture() {
      return new GroceryImportEventCapture();
    }
  }

  static class GroceryImportEventCapture {
    private final List<ItemAddedFromGroceryEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onAddedFromGrocery(ItemAddedFromGroceryEvent event) {
      events.add(event);
    }

    public List<ItemAddedFromGroceryEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
