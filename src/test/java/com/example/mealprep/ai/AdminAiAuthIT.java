package com.example.mealprep.ai;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AdminAccessProperties;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-context auth IT for the AI admin observability surface (finding {@code ai-10}). Drives the
 * real deny-by-default {@code AuthSecurityConfig} chain plus the real shared {@code
 * AdminAccessGuard}.
 *
 * <p><b>Consolidation note.</b> The AI module's original {@code AiAdminGuard} + {@code
 * mealprep.ai.admin.user-ids} key were migrated to the project-wide {@code AdminAccessGuard}
 * (auth.api) backed by the single allowlist {@code mealprep.admin.user-ids}. This IT now exercises
 * that shared key: the real guard resolves the caller and checks the (here-mocked) {@link
 * AdminAccessProperties} allowlist — only the config record is mocked so a registered user's
 * runtime-random UUID can be designated admin; the 401/403/200 branch logic is the production
 * guard.
 *
 * <p>The trio: anonymous ⇒ 401 (the filter chain, before the controller), authenticated-non-admin ⇒
 * 403 (empty/no-match allowlist, fail-closed), allowlisted-admin ⇒ 200.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class AdminAiAuthIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthProperties authProperties;
  @Autowired private JdbcTemplate jdbcTemplate;

  // Only the allowlist config record is mocked (default: everyone non-admin ⇒ fail-closed 403). The
  // shared AdminAccessGuard bean itself is the real production component under test.
  @MockBean private AdminAccessProperties adminProperties;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM auth_sessions");
    jdbcTemplate.update("DELETE FROM auth_login_attempts");
    jdbcTemplate.update("DELETE FROM auth_users");
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerAndLogin() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("ai-admin-" + AuthTestData.shortId());
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    UUID userId =
        UUID.fromString(
            objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("userId")
                .asText());
    return new AuthedUser(userId, cookie);
  }

  @Test
  void costSummary_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/admin/ai/cost-summary")).andExpect(status().isUnauthorized());
  }

  @Test
  void costSummary_returns403_whenAuthenticatedButNotAdmin() throws Exception {
    // Default mock: isAdmin(...) returns false for every user ⇒ fail-closed.
    AuthedUser user = registerAndLogin();
    mvc.perform(get("/api/v1/admin/ai/cost-summary").cookie(user.cookie()))
        .andExpect(status().isForbidden());
  }

  @Test
  void callLog_returns403_whenAuthenticatedButNotAdmin() throws Exception {
    AuthedUser user = registerAndLogin();
    mvc.perform(get("/api/v1/admin/ai/call-log").cookie(user.cookie()))
        .andExpect(status().isForbidden());
  }

  @Test
  void promptTemplates_returns403_whenAuthenticatedButNotAdmin() throws Exception {
    AuthedUser user = registerAndLogin();
    mvc.perform(get("/api/v1/admin/ai/prompt-templates").cookie(user.cookie()))
        .andExpect(status().isForbidden());
  }

  @Test
  void costSummary_returns200_whenAllowlistedAdmin() throws Exception {
    AuthedUser user = registerAndLogin();
    given(adminProperties.isAdmin(user.userId())).willReturn(true);

    mvc.perform(get("/api/v1/admin/ai/cost-summary").cookie(user.cookie()))
        .andExpect(status().isOk());
  }
}
