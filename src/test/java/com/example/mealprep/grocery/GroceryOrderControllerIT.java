package com.example.mealprep.grocery;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.grocery.api.dto.CancelOrderRequest;
import com.example.mealprep.grocery.api.dto.CreateOrderRequest;
import com.example.mealprep.grocery.api.dto.ProviderConnectionRequest;
import com.example.mealprep.grocery.api.dto.ResolveSubstitutionRequest;
import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import com.example.mealprep.grocery.domain.service.internal.providers.FakeGroceryProvider;
import com.example.mealprep.grocery.domain.service.internal.providers.FakeGroceryProvider.FailureMode;
import com.example.mealprep.grocery.testdata.GroceryTestData;
import com.example.mealprep.grocery.testsupport.FakeGroceryProviderConfig;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP flow over the Tier-3 order endpoints (grocery-01e) against real Postgres (Testcontainers) +
 * the {@link FakeGroceryProvider}. Exercises the lifecycle through markDelivered (create → quote →
 * place → confirm → delivered), the error codes (200/201/404/409/422/503), the partial-place 200
 * fail-forward, and that quote prices DERIVE from the reference snapshot. OpenAPI-validated on the
 * happy responses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class, FakeGroceryProviderConfig.class})
@ActiveProfiles("test")
class GroceryOrderControllerIT {

  private static final String BASE = "/api/v1/grocery/orders";
  private static final String PROVIDER = "fake";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private FakeGroceryProvider fakeGroceryProvider;

  @BeforeEach
  void resetProvider() {
    fakeGroceryProvider.reset();
  }

  @AfterEach
  void cleanup() {
    fakeGroceryProvider.reset();
    jdbcTemplate.update("DELETE FROM provision_grocery_import_log");
    jdbcTemplate.update("DELETE FROM provision_inventory_audit");
    jdbcTemplate.update("DELETE FROM provision_inventory");
    jdbcTemplate.update("DELETE FROM provision_supplier_products");
    jdbcTemplate.update("DELETE FROM grocery_substitution_proposals");
    jdbcTemplate.update("DELETE FROM grocery_price_history");
    jdbcTemplate.update("DELETE FROM grocery_order_lines");
    jdbcTemplate.update("DELETE FROM grocery_orders");
    jdbcTemplate.update("DELETE FROM grocery_provider_state");
    jdbcTemplate.update("DELETE FROM shopping_list_lines");
    jdbcTemplate.update("DELETE FROM shopping_lists");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  // ---------------- fixtures ----------------

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("go-" + AuthTestData.shortId());
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    String userId =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
    return new AuthedUser(UUID.fromString(userId), cookie);
  }

  private UUID seedList(UUID userId) {
    UUID listId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO shopping_lists (id, user_id, household_id, plan_id, plan_generation,"
            + " generated_at, estimated_total_currency, stale_ingredient_count,"
            + " pantry_tracking_enabled, version, created_at, updated_at)"
            + " VALUES (?, ?, NULL, ?, 1, now(), 'GBP', 0, false, 0, now(), now())",
        listId,
        userId,
        UUID.randomUUID());
    return listId;
  }

  private void seedLine(UUID listId, String key, String displayName, int packSizeG, int packCount) {
    jdbcTemplate.update(
        "INSERT INTO shopping_list_lines (id, shopping_list_id, ingredient_mapping_key,"
            + " display_name, requested_quantity, requested_unit, suggested_pack_size_g,"
            + " suggested_pack_count, line_type, is_stale_estimate, fulfilment_status, created_at,"
            + " updated_at) VALUES (?, ?, ?, ?, 1.000, 'kg', ?, ?, 'PLANNED_DEMAND', false,"
            + " 'UNFILLED', now(), now())",
        UUID.randomUUID(),
        listId,
        key,
        displayName,
        packSizeG,
        packCount);
  }

  private void enableProvider(Cookie cookie) throws Exception {
    ProviderConnectionRequest req = new ProviderConnectionRequest(PROVIDER, true, false, 50);
    mvc.perform(
            put(BASE + "/providers/" + PROVIDER)
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(openApi().isValid(openApiValidator));
  }

  private UUID createDraft(Cookie cookie, UUID listId) throws Exception {
    CreateOrderRequest req = new CreateOrderRequest(listId, PROVIDER);
    MvcResult result =
        mvc.perform(
                post(BASE)
                    .cookie(cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();
    return UUID.fromString(
        objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
  }

  // ---------------- auth ----------------

  @Test
  void create_anonymous_returns401() throws Exception {
    mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }

  // ---------------- create ----------------

  @Test
  void create_noProviderState_returns422() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId());
    seedLine(listId, "white rice", "White rice", 500, 2);

    CreateOrderRequest req = new CreateOrderRequest(listId, PROVIDER);
    mvc.perform(
            post(BASE)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void create_unknownList_returns404() throws Exception {
    AuthedUser user = registerUser();
    enableProvider(user.cookie());

    CreateOrderRequest req = new CreateOrderRequest(UUID.randomUUID(), PROVIDER);
    mvc.perform(
            post(BASE)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isNotFound());
  }

  @Test
  void create_clonesLines_returns201() throws Exception {
    AuthedUser user = registerUser();
    enableProvider(user.cookie());
    UUID listId = seedList(user.userId());
    seedLine(listId, "white rice", "White rice", 500, 2);
    seedLine(listId, "broccoli", "Broccoli", 300, 1);

    UUID orderId = createDraft(user.cookie(), listId);

    mvc.perform(get(BASE + "/" + orderId).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines.length()").value(2))
        .andExpect(jsonPath("$.status").value("DRAFT"));
  }

  // ---------------- quote ----------------

  @Test
  void quote_happy_returnsQuoted_priceDerivesFromReference() throws Exception {
    AuthedUser user = registerUser();
    enableProvider(user.cookie());
    UUID listId = seedList(user.userId());
    // "white rice" is in the bundled reference snapshot at 18 p/100g; packCount 2 → unit 18, line
    // 36.
    seedLine(listId, "white rice", "White rice", 500, 2);
    UUID orderId = createDraft(user.cookie(), listId);

    mvc.perform(post(BASE + "/" + orderId + "/quote").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("QUOTED"))
        .andExpect(jsonPath("$.providerOrderId").isNotEmpty())
        .andExpect(jsonPath("$.quotedTotalPence").value(36)) // 18 × 2 packs
        .andExpect(jsonPath("$.lines[0].quotedUnitPence").value(18))
        .andExpect(openApi().isValid(openApiValidator));

    // One QUOTE observation per quoted line (weight 0.85).
    Long quoteRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM grocery_price_history WHERE source = 'QUOTE'", Long.class);
    assertThat(quoteRows).isEqualTo(1L);
  }

  @Test
  void quote_providerUnavailable_returns503_movesToProviderUnavailable() throws Exception {
    AuthedUser user = registerUser();
    enableProvider(user.cookie());
    UUID listId = seedList(user.userId());
    seedLine(listId, "white rice", "White rice", 500, 1);
    UUID orderId = createDraft(user.cookie(), listId);

    fakeGroceryProvider.setFailureMode(FailureMode.UNAVAILABLE);
    mvc.perform(post(BASE + "/" + orderId + "/quote").cookie(user.cookie()))
        .andExpect(status().isServiceUnavailable());

    // The PROVIDER_UNAVAILABLE state + event were committed before the 503.
    String dbStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM grocery_orders WHERE id = ?", String.class, orderId);
    assertThat(dbStatus).isEqualTo("PROVIDER_UNAVAILABLE");
  }

  @Test
  void quote_aiUnavailable_revertsToDraft_returns503() throws Exception {
    AuthedUser user = registerUser();
    enableProvider(user.cookie());
    UUID listId = seedList(user.userId());
    seedLine(listId, "white rice", "White rice", 500, 1);
    UUID orderId = createDraft(user.cookie(), listId);

    fakeGroceryProvider.setFailureMode(FailureMode.AI_UNAVAILABLE);
    mvc.perform(post(BASE + "/" + orderId + "/quote").cookie(user.cookie()))
        .andExpect(status().isServiceUnavailable());

    String dbStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM grocery_orders WHERE id = ?", String.class, orderId);
    assertThat(dbStatus).isEqualTo("DRAFT");
    String reason =
        jdbcTemplate.queryForObject(
            "SELECT status_reason FROM grocery_orders WHERE id = ?", String.class, orderId);
    assertThat(reason).contains("AI cost cap");
  }

  // ---------------- place ----------------

  @Test
  void place_happy_autoAdvancesToAwaitingConfirmation_setsConfirmLink() throws Exception {
    AuthedUser user = registerUser();
    UUID orderId = quotedOrder(user);

    mvc.perform(post(BASE + "/" + orderId + "/place").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AWAITING_USER_CONFIRMATION"))
        .andExpect(jsonPath("$.confirmLink").isNotEmpty())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void place_partial_returns200_placedPartial() throws Exception {
    AuthedUser user = registerUser();
    enableProvider(user.cookie());
    UUID listId = seedList(user.userId());
    seedLine(listId, "white rice", "White rice", 500, 1);
    seedLine(listId, "broccoli", "Broccoli", 300, 1);
    UUID orderId = createDraft(user.cookie(), listId);
    mvc.perform(post(BASE + "/" + orderId + "/quote").cookie(user.cookie()))
        .andExpect(status().isOk());

    fakeGroceryProvider.setFailureMode(FailureMode.PARTIAL);
    // A partial place is fail-forward — 200, status PLACED_PARTIAL, confirm link present.
    mvc.perform(post(BASE + "/" + orderId + "/place").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confirmLink").isNotEmpty())
        // auto-advances to AWAITING_USER_CONFIRMATION even from PLACED_PARTIAL
        .andExpect(jsonPath("$.status").value("AWAITING_USER_CONFIRMATION"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void place_fromDraft_returns409_illegalTransition() throws Exception {
    AuthedUser user = registerUser();
    enableProvider(user.cookie());
    UUID listId = seedList(user.userId());
    seedLine(listId, "white rice", "White rice", 500, 1);
    UUID orderId = createDraft(user.cookie(), listId);

    // Place without quoting first — DRAFT → PLACED is illegal.
    mvc.perform(post(BASE + "/" + orderId + "/place").cookie(user.cookie()))
        .andExpect(status().isConflict());
  }

  // ---------------- confirm / deliver ----------------

  @Test
  void fullLifecycle_noSubstitution_markDeliveredReconcilesStraightThrough() throws Exception {
    AuthedUser user = registerUser();
    UUID orderId = quotedOrder(user);

    mvc.perform(post(BASE + "/" + orderId + "/place").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AWAITING_USER_CONFIRMATION"));

    mvc.perform(post(BASE + "/" + orderId + "/mark-user-confirmed").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(openApi().isValid(openApiValidator));

    // 01f: a no-substitution mark-delivered runs tryReconcile inline → straight to RECONCILED.
    mvc.perform(post(BASE + "/" + orderId + "/mark-delivered").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RECONCILED"))
        .andExpect(jsonPath("$.outstandingProposals.length()").value(0))
        .andExpect(openApi().isValid(openApiValidator));

    // Reconciliation wrote a PAID observation for the delivered line.
    Long paidRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM grocery_price_history WHERE source = 'PAID'", Long.class);
    assertThat(paidRows).isEqualTo(1L);
  }

  @Test
  void fullLifecycle_throughResolveAndReconcile() throws Exception {
    AuthedUser user = registerUser();
    UUID orderId = quotedOrder(user);

    mvc.perform(post(BASE + "/" + orderId + "/place").cookie(user.cookie()))
        .andExpect(status().isOk());
    mvc.perform(post(BASE + "/" + orderId + "/mark-user-confirmed").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));

    // Provider reports DELIVERED with one substitution → persisted PENDING_USER_REVIEW.
    fakeGroceryProvider.setDelivered(true);
    fakeGroceryProvider.setSubstitutions(
        java.util.List.of(GroceryTestData.providerSubstitution("white rice", "Brown rice")));
    mvc.perform(post(BASE + "/" + orderId + "/refresh-status").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DELIVERED"))
        // blocked while pending — outstanding proposal surfaced on the order detail
        .andExpect(jsonPath("$.outstandingProposals.length()").value(1))
        .andExpect(openApi().isValid(openApiValidator));

    // GET the outstanding substitutions (200).
    MvcResult subs =
        mvc.perform(get(BASE + "/" + orderId + "/substitutions").cookie(user.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();
    UUID proposalId =
        UUID.fromString(
            objectMapper
                .readTree(subs.getResponse().getContentAsString())
                .get(0)
                .get("id")
                .asText());

    // Resolve (ACCEPTED) → clears the gate → reconciliation runs.
    ResolveSubstitutionRequest req =
        new ResolveSubstitutionRequest(proposalId, SubstitutionProposalStatus.ACCEPTED);
    mvc.perform(
            post(BASE + "/" + orderId + "/substitutions/" + proposalId + "/resolve")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.proposalStatus").value("ACCEPTED"))
        .andExpect(openApi().isValid(openApiValidator));

    // Order reconciled; outstanding proposals now empty; PAID observation written.
    mvc.perform(get(BASE + "/" + orderId).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RECONCILED"))
        .andExpect(jsonPath("$.outstandingProposals.length()").value(0));

    Long paidRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM grocery_price_history WHERE source = 'PAID'", Long.class);
    assertThat(paidRows).isEqualTo(1L);
  }

  @Test
  void resolve_alreadyResolvedProposal_returns409() throws Exception {
    AuthedUser user = registerUser();
    UUID orderId = quotedOrder(user);
    mvc.perform(post(BASE + "/" + orderId + "/place").cookie(user.cookie()))
        .andExpect(status().isOk());
    mvc.perform(post(BASE + "/" + orderId + "/mark-user-confirmed").cookie(user.cookie()))
        .andExpect(status().isOk());

    // Two substitutions so resolving one leaves the order un-reconciled (proposal stays resolvable
    // to test re-resolve, while the order is still DELIVERED).
    fakeGroceryProvider.setDelivered(true);
    fakeGroceryProvider.setSubstitutions(
        java.util.List.of(
            GroceryTestData.providerSubstitution("white rice", "Brown rice"),
            GroceryTestData.providerSubstitution("other", "Sub other")));
    mvc.perform(post(BASE + "/" + orderId + "/refresh-status").cookie(user.cookie()))
        .andExpect(status().isOk());

    MvcResult subs =
        mvc.perform(get(BASE + "/" + orderId + "/substitutions").cookie(user.cookie()))
            .andExpect(status().isOk())
            .andReturn();
    UUID proposalId =
        UUID.fromString(
            objectMapper
                .readTree(subs.getResponse().getContentAsString())
                .get(0)
                .get("id")
                .asText());

    ResolveSubstitutionRequest req =
        new ResolveSubstitutionRequest(proposalId, SubstitutionProposalStatus.ACCEPTED);
    mvc.perform(
            post(BASE + "/" + orderId + "/substitutions/" + proposalId + "/resolve")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk());

    // Re-resolving the same (now ACCEPTED) proposal is a 409.
    mvc.perform(
            post(BASE + "/" + orderId + "/substitutions/" + proposalId + "/resolve")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isConflict());
  }

  @Test
  void substitutions_unknownOrder_returnsEmpty200() throws Exception {
    AuthedUser user = registerUser();
    // An order with no proposals yields an empty list (200) — the endpoint does not 404 on absence.
    mvc.perform(get(BASE + "/" + UUID.randomUUID() + "/substitutions").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void markUserConfirmed_fromDraft_returns409() throws Exception {
    AuthedUser user = registerUser();
    enableProvider(user.cookie());
    UUID listId = seedList(user.userId());
    seedLine(listId, "white rice", "White rice", 500, 1);
    UUID orderId = createDraft(user.cookie(), listId);

    mvc.perform(post(BASE + "/" + orderId + "/mark-user-confirmed").cookie(user.cookie()))
        .andExpect(status().isConflict());
  }

  @Test
  void markUserConfirmed_unknownOrder_returns404() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(post(BASE + "/" + UUID.randomUUID() + "/mark-user-confirmed").cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  // ---------------- cancel ----------------

  @Test
  void cancel_draftOrder_returns200_cancelled() throws Exception {
    AuthedUser user = registerUser();
    enableProvider(user.cookie());
    UUID listId = seedList(user.userId());
    seedLine(listId, "white rice", "White rice", 500, 1);
    UUID orderId = createDraft(user.cookie(), listId);

    CancelOrderRequest req = new CancelOrderRequest(orderId, "changed mind");
    mvc.perform(
            post(BASE + "/" + orderId + "/cancel")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- provider state ----------------

  @Test
  void getProviderState_unknown_returns404() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(get(BASE + "/providers/" + PROVIDER).cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void upsertThenGetProviderState_returns200() throws Exception {
    AuthedUser user = registerUser();
    enableProvider(user.cookie());
    mvc.perform(get(BASE + "/providers/" + PROVIDER).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.providerKey").value(PROVIDER))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- helpers ----------------

  /** Register + enable provider + seed a one-line list + create draft + quote → returns orderId. */
  private UUID quotedOrder(AuthedUser user) throws Exception {
    enableProvider(user.cookie());
    UUID listId = seedList(user.userId());
    seedLine(listId, "white rice", "White rice", 500, 1);
    UUID orderId = createDraft(user.cookie(), listId);
    mvc.perform(post(BASE + "/" + orderId + "/quote").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("QUOTED"));
    return orderId;
  }
}
