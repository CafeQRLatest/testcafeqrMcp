package com.restaurant.pos.ai;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.restaurant.pos.ai.backend.BusinessInfoService;
import com.restaurant.pos.ai.backend.BusinessInfoService.BusinessInfo;
import com.restaurant.pos.ai.backend.CafeQrApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cafeqrai;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.ai.model.chat=ollama",
        "cafeqr.ai.limits.monthly-questions-per-client=2"
})
@AutoConfigureMockMvc
class CafeQrAiIntegrationTest {

    private static final KeyPair KEYS = generateKeys();
    private static final KeyPair OTHER_KEYS = generateKeys();

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("cafeqr.ai.security.jwt-public-key",
                () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CafeQrApiClient api;

    @MockitoBean
    BusinessInfoService businessInfo;

    @MockitoBean
    ChatModel chatModel;

    private final UUID clientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(businessInfo.get(any())).thenReturn(new BusinessInfo("Test Cafe", "AED", "Asia/Dubai", "AE"));
        when(chatModel.getOptions()).thenReturn(org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build());
    }

    // ---- security -------------------------------------------------------------------------------------

    @Test
    void rejectsMissingWrongScopeWrongRoleAndForeignTokens() throws Exception {
        String list = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        mvc.perform(mcp(list, null)).andExpect(status().isUnauthorized());
        mvc.perform(mcp(list, token(KEYS, null, "SUPER_ADMIN"))).andExpect(status().isUnauthorized());     // normal login token
        mvc.perform(mcp(list, token(KEYS, "ai:read", "STAFF"))).andExpect(status().isUnauthorized()); // staff not allowed
        mvc.perform(mcp(list, token(KEYS, "ai:read", "ADMIN"))).andExpect(status().isUnauthorized());     // only super admins for now
        mvc.perform(mcp(list, token(KEYS, "ai:read", "MANAGER"))).andExpect(status().isUnauthorized());
        mvc.perform(mcp(list, token(OTHER_KEYS, "ai:read", "SUPER_ADMIN"))).andExpect(status().isUnauthorized()); // not signed by CafeQR
        mvc.perform(get("/api/assistant/usage")).andExpect(status().isUnauthorized());
    }

    // ---- MCP --------------------------------------------------------------------------------------------

    @Test
    void mcpListsReadOnlyToolsAndCallsBackendWithOwnersToken() throws Exception {
        String ownerToken = token(KEYS, "ai:read", "SUPER_ADMIN");

        String tools = mvc.perform(mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", ownerToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(tools).contains("get_sales_summary").contains("get_stock_levels").contains("\"readOnlyHint\":true");
        assertThat(tools).doesNotContain("\"readOnlyHint\":false");

        when(api.get(any(), eq("/api/v1/reports/sales-summary"), anyMap()))
                .thenReturn(JsonMapper.builder().build().readTree("{\"totalOrders\":7,\"grandTotal\":980.5}"));
        String call = mvc.perform(mcp("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call",
                         "params":{"name":"get_sales_summary","arguments":{"from":"2026-11-01","to":"2026-11-05"}}}""",
                        ownerToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(call).contains("total_orders").contains("980.5").contains("AED");
        // the backend is called with the owner's own token, so backend tenant checks apply
        org.mockito.Mockito.verify(api).get(org.mockito.ArgumentMatchers.argThat(o -> o.token().equals(ownerToken)),
                eq("/api/v1/reports/sales-summary"), anyMap());
    }

    @Test
    void mcpReportsToolInputErrorsToTheModel() throws Exception {
        String call = mvc.perform(mcp("""
                        {"jsonrpc":"2.0","id":3,"method":"tools/call",
                         "params":{"name":"get_sales_summary","arguments":{"from":"yesterday"}}}""",
                        token(KEYS, "ai:read", "SUPER_ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(call).contains("YYYY-MM-DD").contains("\"isError\":true");
    }

    // ---- built-in chat ----------------------------------------------------------------------------------

    @Test
    void chatStreamsAnswerStoresHistoryAndEnforcesMonthlyQuota() throws Exception {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("You had 7 orders ")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("today."))))));
        String ownerToken = token(KEYS, "ai:read", "SUPER_ADMIN");

        String first = chat(ownerToken, "How many orders today?");
        assertThat(first).contains("event:started").contains("event:token").contains("You had 7 orders").contains("event:done");

        String history = mvc.perform(get("/api/assistant/conversations").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(history).contains("How many orders today?");

        chat(ownerToken, "And yesterday?"); // second question uses the last of this café's quota (2)
        mvc.perform(post("/api/assistant/chat").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"One more?\"}"))
                .andExpect(status().isTooManyRequests());

        String usage = mvc.perform(get("/api/assistant/usage").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(usage).contains("\"used\":2").contains("\"limit\":2");
    }

    @Test
    void ownersCannotOpenAnotherUsersConversation() throws Exception {
        mvc.perform(get("/api/assistant/conversations/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token(KEYS, "ai:read", "SUPER_ADMIN")))
                .andExpect(status().isNotFound());
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private String chat(String token, String message) throws Exception {
        MvcResult result = mvc.perform(post("/api/assistant/chat").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(10_000);
        return result.getResponse().getContentAsString();
    }

    private static org.springframework.test.web.servlet.RequestBuilder mcp(String body, String token) {
        var req = post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body);
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return req;
    }

    private String token(KeyPair keys, String scope, String role) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("owner@cafe.test")
                .claim("clientId", clientId.toString())
                .claim("orgId", UUID.randomUUID().toString())
                .claim("userId", UUID.nameUUIDFromBytes(clientId.toString().getBytes()).toString())
                .claim("role", role)
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 600_000));
        if (scope != null) {
            claims.claim("scope", scope);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair generateKeys() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
